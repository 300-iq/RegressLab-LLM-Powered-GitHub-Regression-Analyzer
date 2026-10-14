package dev.regresslab.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.regresslab.api.ApiProblem;
import dev.regresslab.domain.ApiDtos.ImportResult;
import dev.regresslab.github.GithubDtos.*;
import dev.regresslab.ingestion.Digests;
import dev.regresslab.ingestion.IngestionService;
import dev.regresslab.persistence.BenchmarkRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class GithubPersistence {
    private final IngestionService ingestion;
    private final BenchmarkRepository results;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public GithubPersistence(IngestionService ingestion,BenchmarkRepository results,JdbcTemplate jdbc,ObjectMapper mapper) {
        this.ingestion=ingestion; this.results=results; this.jdbc=jdbc; this.mapper=GithubJson.metadataMapper(mapper);
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public ImportPair persist(VerifiedPair pair) {
        return new ImportPair(persistOne(pair.baseline()),persistOne(pair.candidate()));
    }
    private ImportResult persistOne(VerifiedArchive verified) {
        var archive=verified.archive();
        var result=ingestion.ingest(archive.artifact(),archive.runBytes());
        var existing=jdbc.queryForList("SELECT github_artifact_id, archive_sha256 FROM github_artifact_imports WHERE ingested_artifact_id = ?",result.artifactId());
        if (!existing.isEmpty()) {
            if (((Number)existing.getFirst().get("github_artifact_id")).longValue()!=verified.githubArtifactId()
                    || !existing.getFirst().get("archive_sha256").equals(Digests.bytes(archive.archiveBytes())))
                throw ApiProblem.conflict("Stored GitHub archive identity differs.");
        } else {
            jdbc.update("INSERT INTO github_artifact_imports(ingested_artifact_id,github_artifact_id,archive_sha256,archive_bytes,execution,verification_evidence) VALUES (?,?,?,?,?::jsonb,?::jsonb)",
                    result.artifactId(),verified.githubArtifactId(),Digests.bytes(archive.archiveBytes()),archive.archiveBytes(),results.json(archive.execution()),results.json(verified.evidence()));
        }
        jdbc.update("UPDATE repositories SET provenance = 'GITHUB_METADATA' WHERE id = ?",result.repositoryId());
        var pr=archive.artifact().pullRequest();
        int matched=jdbc.update("""
                UPDATE pull_requests SET provenance='GITHUB_METADATA'
                WHERE id=? AND number=? AND title=? AND state=? AND baseline_sha=? AND candidate_sha=?
                """,result.pullRequestId(),pr.number(),pr.title(),pr.state().name(),pr.baselineSha(),pr.candidateSha());
        if (matched!=1) throw ApiProblem.conflict("Stored PR snapshot differs from the verified artifact snapshot.");
        jdbc.update("UPDATE benchmark_runs SET provenance = 'GITHUB_METADATA' WHERE id = ?",result.runId());
        return new ImportResult(result.repositoryId(),result.pullRequestId(),result.runId(),result.artifactId(),result.artifactSha256(),result.dataOrigin(),"GITHUB_METADATA",result.replayed());
    }
    public record Audit(long githubArtifactId,String archiveSha256,Instant verifiedAt,JsonNode execution,JsonNode verificationEvidence) {}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Audit audit(UUID runId) {
        results.run(runId);
        var rows=jdbc.query("SELECT g.* FROM github_artifact_imports g JOIN benchmark_runs r ON r.artifact_id=g.ingested_artifact_id WHERE r.id=?",
                (r,n)->new Audit(r.getLong("github_artifact_id"),r.getString("archive_sha256"),r.getTimestamp("verified_at").toInstant(),parse(r.getString("execution")),parse(r.getString("verification_evidence"))),runId);
        if (rows.isEmpty()) throw ApiProblem.notFound("No GitHub verification evidence for this run.");
        return rows.getFirst();
    }
    private JsonNode parse(String json) { try { return mapper.readTree(json); } catch (java.io.IOException exception) { throw new IllegalStateException("Invalid stored audit JSON",exception); } }
}
