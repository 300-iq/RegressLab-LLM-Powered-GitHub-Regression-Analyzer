package dev.regresslab.github;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.regresslab.github.GithubDtos.Pipeline;
import dev.regresslab.security.LocalIngestionSettings;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
@Component
@EnableConfigurationProperties(GithubSettings.class)
public class GithubPipeline {
    private final Pipeline pipeline;
    public GithubPipeline(GithubSettings settings, LocalIngestionSettings local, ResourceLoader resources, ObjectMapper mapper) {
        if (!settings.enabled()) { pipeline=null; return; }
        if (settings.readToken()!=null && !settings.readToken().isEmpty() && !settings.readToken().matches("[A-Za-z0-9_]{1,256}"))
            throw new IllegalStateException("Invalid GitHub read credential format.");
        if (!local.enabled() || !(settings.pipeline().startsWith("classpath:") || settings.pipeline().startsWith("file:")))
            throw new IllegalStateException("GitHub import requires protected local ingestion and a local reviewed pipeline file.");
        try (var input=resources.getResource(settings.pipeline()).getInputStream()) {
            byte[] bytes=input.readNBytes(16385);
            if (bytes.length>16384) throw new IllegalArgumentException();
            pipeline=mapper.readValue(bytes,Pipeline.class);
            if (pipeline.repositoryGithubId()!=local.repositoryGithubId() || !pipeline.repositoryFullName().equals(local.repositoryFullName())
                    || !pipeline.workflowPath().equals(".github/workflows/benchmark.yml") || !pipeline.harnessSha256().matches("[0-9a-f]{64}")
                    || pipeline.files().size()!=5 || !pipeline.files().containsKey(pipeline.workflowPath())
                    || pipeline.files().entrySet().stream().anyMatch(e -> e.getKey().contains("..") || !e.getValue().matches("[0-9a-f]{40}")))
                throw new IllegalArgumentException();
        } catch (Exception exception) { throw new IllegalStateException("Invalid reviewed GitHub pipeline configuration.",exception); }
    }
    public Pipeline configuration() { return pipeline; }
}
