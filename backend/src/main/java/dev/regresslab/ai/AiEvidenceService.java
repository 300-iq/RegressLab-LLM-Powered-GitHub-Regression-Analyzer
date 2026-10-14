package dev.regresslab.ai;

import dev.regresslab.ai.AiDtos.*;
import dev.regresslab.api.ApiProblem;
import dev.regresslab.domain.ArtifactDtos.DataOrigin;
import dev.regresslab.persistence.AnalysisRepository;
import dev.regresslab.persistence.BenchmarkRepository;
import dev.regresslab.security.LocalIngestionSettings;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AiEvidenceService {
    public record Snapshot(String repository, int prNumber, String baselineSha, String candidateSha, Evidence evidence) {}
    private final BenchmarkRepository benchmarks;
    private final AnalysisRepository analyses;
    private final LocalIngestionSettings authorized;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;
    public AiEvidenceService(BenchmarkRepository benchmarks, AnalysisRepository analyses, LocalIngestionSettings authorized, com.fasterxml.jackson.databind.ObjectMapper mapper) {
        this.benchmarks = benchmarks; this.analyses = analyses; this.authorized = authorized; this.mapper=mapper;
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Snapshot snapshot(UUID comparisonId, UUID analysisId) {
        var comparison = benchmarks.comparison(comparisonId); var analysis = analyses.get(analysisId);
        var repo = benchmarks.repository(comparison.repositoryId());
        var baseline = benchmarks.run(comparison.baselineRunId()); var candidate = benchmarks.run(comparison.candidateRunId());
        if (!authorized.enabled() || !repo.fullName().equals(authorized.repositoryFullName())
                || benchmarks.githubRepositoryId(repo.id()) != authorized.repositoryGithubId()
                || comparison.dataOrigin() != DataOrigin.REAL || analysis.dataOrigin() != DataOrigin.REAL
                || !baseline.provenance().equals("GITHUB_METADATA") || !candidate.provenance().equals("GITHUB_METADATA")
                || !analysis.provenance().equals("GITHUB_METADATA"))
            throw ApiProblem.conflict("AI investigation requires authorized REAL measurements with checked GitHub artifact origin.");
        if (!analysis.comparisonId().equals(comparisonId) || !analysis.analysisVersion().equals("fork-bootstrap-v2")
                || analysis.result().classification() == dev.regresslab.analysis.AnalysisDtos.Classification.INVALID_COMPARISON
                || !baseline.pullRequestId().equals(candidate.pullRequestId())
                || !baseline.baselineSha().equals(candidate.baselineSha()) || !baseline.candidateSha().equals(candidate.candidateSha())
                || !baseline.commitSha().equals(candidate.baselineSha()) || !candidate.commitSha().equals(candidate.candidateSha()))
            throw ApiProblem.conflict("Analysis or run snapshot is incompatible with this investigation.");
        var pr = benchmarks.pr(candidate.pullRequestId());
        if (!pr.baselineSha().equals(baseline.commitSha()) || !pr.candidateSha().equals(candidate.commitSha()))
            throw ApiProblem.conflict("Historical measurements do not match the current stored PR snapshot.");
        if (benchmarks.runBenchmark(baseline.id(),comparison.benchmarkCaseId()).sampleCount() > 100
                || benchmarks.runBenchmark(candidate.id(),comparison.benchmarkCaseId()).sampleCount() > 100)
            throw ApiProblem.conflict("This bounded investigator supports at most 100 observations per arm.");
        var bs = benchmarks.allSamples(baseline.id(), comparison.benchmarkCaseId());
        var cs = benchmarks.allSamples(candidate.id(), comparison.benchmarkCaseId());
        if (bs.isEmpty() || cs.isEmpty() || analysis.result().baseline() == null || analysis.result().candidate() == null
                || bs.size() != analysis.result().baseline().rawMeasurements().count()
                || cs.size() != analysis.result().candidate().rawMeasurements().count())
            throw ApiProblem.conflict("Complete raw measurements and their analysis are required.");
        var b=benchmarks.runBenchmark(baseline.id(),comparison.benchmarkCaseId());
        var c=benchmarks.runBenchmark(candidate.id(),comparison.benchmarkCaseId());
        var conditions=mapper.valueToTree(java.util.Map.of("baselineEnvironment",baseline.environment(),"candidateEnvironment",candidate.environment(),
                "baselineParameters",b.parameters(),"candidateParameters",c.parameters(),"baselineConfiguration",b.configuration(),"candidateConfiguration",c.configuration()));
        return new Snapshot(repo.fullName(),pr.number(),baseline.commitSha(),candidate.commitSha(),
                new Evidence(analysis,baseline.githubUrl(),candidate.githubUrl(),bs,cs,baseline.environmentSha256(),candidate.environmentSha256(),conditions));
    }
}
