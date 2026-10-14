package dev.regresslab.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.regresslab.api.ApiProblem;
import dev.regresslab.domain.ApiDtos.ImportResult;
import dev.regresslab.domain.ArtifactDtos.Artifact;
import dev.regresslab.persistence.BenchmarkRepository;
import dev.regresslab.security.LocalIngestionSettings;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
public class IngestionService {
    private final BenchmarkRepository repository;
    private final ObjectMapper mapper;
    private final LocalIngestionSettings settings;
    public IngestionService(BenchmarkRepository repository, ObjectMapper mapper, LocalIngestionSettings settings) {
        this.repository = repository; this.mapper = mapper; this.settings = settings;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ImportResult ingest(Artifact artifact, byte[] bytes) {
        if (!settings.enabled() || artifact.repository().githubId() != settings.repositoryGithubId()
                || !artifact.repository().fullName().equals(settings.repositoryFullName()))
            throw new ApiProblem(HttpStatus.FORBIDDEN, "REPOSITORY_NOT_ALLOWED", "Repository is not allowlisted for local ingestion.");
        String digest = Digests.bytes(bytes);
        // Single-repository MVP: one global transaction lock serializes conflicting
        // imports/computations before checking stable identities. All SQL is atomic.
        repository.lockWrites();
        var repo = repository.ensureRepository(artifact.repository(), artifact.dataOrigin());
        var existing = repository.findArtifact(repo, artifact.artifactId());
        if (existing != null) {
            if (!existing.artifactSha256().equals(digest)) throw ApiProblem.conflict("Artifact identifier already exists with different bytes.");
            return new ImportResult(repo, existing.pullRequestId(), existing.id(), existing.artifactId(), digest,
                    existing.dataOrigin(), existing.provenance(), true);
        }
        if (repository.runExists(repo, artifact.run())) throw ApiProblem.conflict("Run identity already exists under a different artifact identifier.");
        var pr = repository.upsertPr(repo, artifact.pullRequest());
        var artifactId = repository.insertArtifact(repo, artifact, bytes, digest);
        var run = repository.insertRun(repo, pr, artifactId, artifact, Digests.canonical(mapper, artifact.run().environment()));
        for (var benchmark : artifact.benchmarks()) {
            var caseId = repository.ensureCase(repo, benchmark);
            String configHash = Digests.canonical(mapper, Map.of("canonicalVersion", "canonical-json-v1",
                    "identifier", benchmark.identifier(), "metric", benchmark.metricName(), "unit", benchmark.unit(),
                    "direction", benchmark.direction(), "parameters", benchmark.parameters(), "configuration", benchmark.configuration()));
            repository.insertBenchmark(repo, run, caseId, benchmark, configHash);
        }
        return new ImportResult(repo, pr, run, artifactId, digest, artifact.dataOrigin(), "LOCAL_UNVERIFIED", false);
    }
}
