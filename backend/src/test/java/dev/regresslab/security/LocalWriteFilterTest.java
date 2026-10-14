package dev.regresslab.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;

class LocalWriteFilterTest {
    private final String token = "test-only-long-token-01234567890123456789";
    private MockEnvironment env(String address) {
        return new MockEnvironment().withProperty("spring.profiles.active", "local-ingestion").withProperty("server.address", address);
    }
    private LocalIngestionSettings settings(String token) { return new LocalIngestionSettings(true, token, 1, "regresslab-demo/java-benchmarks", 30); }
    @Test void refusesPublicBindingsMissingProfilesAndWeakTokens() {
        assertThatThrownBy(() -> new LocalWriteFilter(settings(token), new ObjectMapper(), env("0.0.0.0"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new LocalWriteFilter(settings(token), new ObjectMapper(), new MockEnvironment().withProperty("server.address", "127.0.0.1"))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new LocalWriteFilter(settings("short"), new ObjectMapper(), env("127.0.0.1"))).isInstanceOf(IllegalStateException.class);
    }
    @Test void writeEndpointsAreDisabledByDefault() throws Exception {
        var filter = new LocalWriteFilter(new LocalIngestionSettings(false, null, 0, null, 30), new ObjectMapper(), new MockEnvironment());
        var response = new MockHttpServletResponse();
        filter.doFilter(new MockHttpServletRequest("POST", "/api/v1/dev/artifacts"), response, (r, s) -> { throw new AssertionError("Must not reach controller"); });
        assertThat(response.getStatus()).isEqualTo(404);
    }
    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest("POST", "/api/v1/dev/artifacts");
        request.setRemoteAddr("127.0.0.1"); request.setContentType("application/json");
        request.addHeader("Authorization", "Bearer " + token); request.setContent("{}".getBytes()); return request;
    }
    @Test void enforcesRateLimitBeforeWriting() throws Exception {
        var filter = new LocalWriteFilter(settings(token), new ObjectMapper(), env("127.0.0.1"));
        var calls = new AtomicInteger();
        for (int i = 0; i < 31; i++) {
            var response = new MockHttpServletResponse();
            filter.doFilter(request(), response, (r, s) -> calls.incrementAndGet());
            assertThat(response.getStatus()).isEqualTo(i == 30 ? 429 : 200);
        }
        assertThat(calls.get()).isEqualTo(30);
    }
    @Test void rejectsOversizedBodiesWithoutContentLengthAndCompressedBodies() throws Exception {
        var filter = new LocalWriteFilter(settings(token), new ObjectMapper(), env("127.0.0.1"));
        var request = new MockHttpServletRequest("POST", "/api/v1/dev/artifacts") {
            @Override public long getContentLengthLong() { return -1; }
        };
        request.setRemoteAddr("127.0.0.1"); request.setContentType("application/json"); request.addHeader("Authorization", "Bearer " + token);
        request.setContent(new byte[1048577]);
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (r, s) -> { throw new AssertionError("Oversized body reached controller"); });
        assertThat(response.getStatus()).isEqualTo(413);
        var compressed = request(); compressed.addHeader("Content-Encoding", "gzip");
        var unsupported = new MockHttpServletResponse();
        filter.doFilter(compressed, unsupported, (r, s) -> { throw new AssertionError("Encoded body reached controller"); });
        assertThat(unsupported.getStatus()).isEqualTo(415);
    }
    @Test void acceptsQuotedUtf8CharsetButRejectsActualUtf16Input() throws Exception {
        var filter = new LocalWriteFilter(settings(token), new ObjectMapper(), env("127.0.0.1"));
        var valid = request(); valid.setContentType("application/json; charset=\"utf-8\"");
        var response = new MockHttpServletResponse(); var calls = new AtomicInteger();
        filter.doFilter(valid, response, (r, s) -> calls.incrementAndGet());
        assertThat(calls.get()).isEqualTo(1);
        var invalid = request(); invalid.setContent("{}".getBytes(java.nio.charset.StandardCharsets.UTF_16));
        var badResponse = new MockHttpServletResponse();
        filter.doFilter(invalid, badResponse, (r, s) -> { throw new AssertionError("UTF16 body reached controller"); });
        assertThat(badResponse.getStatus()).isEqualTo(400);
    }
}
