package dev.regresslab.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.regresslab.api.ApiProblem;
import dev.regresslab.ingestion.ArtifactValidator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@EnableConfigurationProperties(LocalIngestionSettings.class)
public class LocalWriteFilter extends OncePerRequestFilter {
    private final LocalIngestionSettings settings;
    private final ObjectMapper mapper;
    private final byte[] expectedToken;
    private long windowStart = System.nanoTime();
    private int requests;

    public LocalWriteFilter(LocalIngestionSettings settings, ObjectMapper mapper, Environment environment) {
        this.settings = settings;
        this.mapper = mapper;
        if (settings.enabled()) {
            if (!environment.acceptsProfiles(Profiles.of("local-ingestion"))
                    || !isLoopback(environment.getProperty("server.address", "")))
                throw new IllegalStateException("Local ingestion requires the local-ingestion profile and a loopback server.address.");
            if (settings.token() == null || settings.token().length() < 32 || settings.token().length() > 256)
                throw new IllegalStateException("Local ingestion requires a 32-256 character bearer token.");
            if (settings.requestsPerMinute() < 1 || settings.requestsPerMinute() > 3000)
                throw new IllegalStateException("Local write rate must be between 1 and 3000 requests per minute.");
            if (settings.repositoryGithubId() <= 0 || settings.repositoryGithubId() > 9007199254740991L
                    || settings.repositoryFullName() == null
                    || !settings.repositoryFullName().matches("[A-Za-z0-9][A-Za-z0-9-]{0,38}/[A-Za-z0-9_.-]{1,100}"))
                throw new IllegalStateException("Local ingestion requires one explicit repository ID and full name allowlist.");
        }
        expectedToken = settings.token() == null ? new byte[0] : settings.token().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getMethod().equals("GET") || request.getMethod().equals("HEAD")) {
            chain.doFilter(request, response);
            return;
        }
        try {
            if (!settings.enabled()) throw ApiProblem.notFound("Local write endpoints are disabled.");
            if (!isLoopback(request.getRemoteAddr()) || request.getHeader("Forwarded") != null
                    || request.getHeader("X-Forwarded-For") != null || request.getHeader("X-Real-IP") != null)
                throw new ApiProblem(HttpStatus.FORBIDDEN, "LOCAL_ONLY", "Writes require a direct loopback connection.");
            String authorization = request.getHeader("Authorization");
            if (authorization == null || authorization.length() > 300 || !authorization.startsWith("Bearer ")
                    || !MessageDigest.isEqual(expectedToken, authorization.substring(7).getBytes(StandardCharsets.UTF_8))) {
                response.setHeader("WWW-Authenticate", "Bearer");
                throw new ApiProblem(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "A valid local ingestion bearer token is required.");
            }
            if (!allowRequest()) {
                response.setHeader("Retry-After", "60");
                throw new ApiProblem(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Local write rate limit exceeded.");
            }
            if (!isUtf8Json(request.getContentType())
                    || (request.getHeader("Content-Encoding") != null && !request.getHeader("Content-Encoding").equalsIgnoreCase("identity")))
                throw new ApiProblem(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "JSON_REQUIRED", "Send uncompressed UTF-8 application/json.");
            if (request.getContentLengthLong() > ArtifactValidator.MAX_BYTES)
                throw new ApiProblem(HttpStatus.PAYLOAD_TOO_LARGE, "ARTIFACT_TOO_LARGE", "Request exceeds 1 MiB.");
            byte[] body = request.getInputStream().readNBytes(ArtifactValidator.MAX_BYTES + 1);
            if (body.length > ArtifactValidator.MAX_BYTES)
                throw new ApiProblem(HttpStatus.PAYLOAD_TOO_LARGE, "ARTIFACT_TOO_LARGE", "Request exceeds 1 MiB.");
            ArtifactValidator.requireUtf8(body);
            chain.doFilter(new BufferedRequest(request, body), response);
        } catch (ApiProblem problem) {
            ProblemDetail detail = ProblemDetail.forStatusAndDetail(problem.status(), problem.getMessage());
            detail.setProperty("code", problem.code());
            response.setStatus(problem.status().value());
            response.setContentType("application/problem+json");
            mapper.writeValue(response.getOutputStream(), detail);
        }
    }

    private synchronized boolean allowRequest() {
        long now = System.nanoTime();
        if (now - windowStart >= 60_000_000_000L) { requests = 0; windowStart = now; }
        return ++requests <= settings.requestsPerMinute();
    }

    static boolean isLoopback(String address) {
        return address.equals("127.0.0.1") || address.equals("::1") || address.equals("0:0:0:0:0:0:0:1");
    }

    private static boolean isUtf8Json(String contentType) {
        if (contentType == null) return false;
        try {
            var media = MediaType.parseMediaType(contentType);
            return media.getType().equalsIgnoreCase("application") && media.getSubtype().equalsIgnoreCase("json")
                    && (media.getCharset() == null || media.getCharset().equals(StandardCharsets.UTF_8));
        } catch (IllegalArgumentException exception) { return false; }
    }

    private static class BufferedRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        BufferedRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body; }
        @Override public ServletInputStream getInputStream() {
            ByteArrayInputStream stream = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public int read() { return stream.read(); }
                @Override public boolean isFinished() { return stream.available() == 0; }
                @Override public boolean isReady() { return true; }
                @Override public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("Synchronous requests only"); }
            };
        }
    }
}
