package dev.regresslab.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.regresslab.api.ApiProblem;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class GithubApi {
    private final ObjectMapper mapper;
    private final GithubSettings settings;
    private final HttpClient client;
    private final URI origin;
    private final Duration deadline;
    @org.springframework.beans.factory.annotation.Autowired
    public GithubApi(ObjectMapper mapper, GithubSettings settings) {
        this(mapper,settings,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build(),URI.create("https://api.github.com"),Duration.ofSeconds(10));
    }
    // Package-private transport seam for actual loopback HTTP tests. No origin or
    // deadline setting is accepted from an artifact, request, or configuration.
    GithubApi(ObjectMapper mapper,GithubSettings settings,HttpClient client,URI origin,Duration deadline) {
        this.mapper=GithubJson.metadataMapper(mapper);this.settings=settings;this.client=client;this.origin=origin;this.deadline=deadline;
    }
    public JsonNode get(String path) {
        // Paths are constructed solely from configured repository names, fixed API
        // routes, validated IDs and hexadecimal SHAs. No artifact URL is followed.
        if (!path.matches("/repos/[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_./-]+)?(?:\\?recursive=1)?"))
            throw new IllegalArgumentException("Invalid fixed GitHub API path");
        var builder=HttpRequest.newBuilder(origin.resolve(path)).timeout(deadline)
                .header("Accept","application/vnd.github+json").header("X-GitHub-Api-Version","2026-03-10")
                .header("User-Agent","RegressLab-public-artifact-importer").GET();
        if (settings.readToken()!=null && !settings.readToken().isBlank()) builder.header("Authorization","Bearer "+settings.readToken());
        var exchange=client.sendAsync(builder.build(),ignored -> new LimitedBodySubscriber(GithubJson.MAX_RESPONSE_BYTES));
        try {
            // Await full bounded body completion, not merely response headers.
            var response=exchange.get(deadline.toMillis(),java.util.concurrent.TimeUnit.MILLISECONDS);
            if (response.statusCode()!=200) throw unavailable();
            var data=mapper.readTree(response.body());
            if (data==null || !data.isObject()) throw unavailable();
            dev.regresslab.ingestion.ArtifactValidator.rejectInvalidText(data);
            return data;
        } catch (InterruptedException exception) {
            exchange.cancel(true);Thread.currentThread().interrupt();throw unavailable();
        } catch (java.util.concurrent.TimeoutException exception) {
            exchange.cancel(true);throw unavailable();
        } catch (java.util.concurrent.ExecutionException | IOException exception) { throw unavailable(); }
    }
    private static ApiProblem unavailable() {
        return new ApiProblem(HttpStatus.SERVICE_UNAVAILABLE,"GITHUB_UNAVAILABLE","GitHub metadata is unavailable, invalid, too large, expired, timed out, or rate limited. Retry later and check server-side read access if needed.");
    }
}
