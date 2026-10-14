package dev.regresslab.analysis;

import dev.regresslab.api.ApiProblem;
import dev.regresslab.domain.ApiDtos.*;
import dev.regresslab.domain.ArtifactDtos.RunStatus;
import dev.regresslab.persistence.BenchmarkRepository;
import java.util.ArrayList;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;

@Service
public class ComparisonService {
    private final BenchmarkRepository repository;
    public ComparisonService(BenchmarkRepository repository) { this.repository = repository; }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ComparisonDto compute(ComparisonRequest input) {
        if (input.baselineRunId().equals(input.candidateRunId())) throw ApiProblem.conflict("Comparison requires distinct runs.");
        repository.lockWrites();
        var existing = repository.findComparison(input, DescriptiveAnalysis.VERSION);
        if (existing != null) return existing;
        var baseline = repository.run(input.baselineRunId()); var candidate = repository.run(input.candidateRunId());
        if (!baseline.repositoryId().equals(candidate.repositoryId()) || !baseline.pullRequestId().equals(candidate.pullRequestId())
                || baseline.dataOrigin() != candidate.dataOrigin()) throw ApiProblem.conflict("Runs must belong to the same repository, pull request and data origin.");
        if (!baseline.baselineSha().equals(candidate.baselineSha()) || !baseline.candidateSha().equals(candidate.candidateSha())
                || !baseline.commitSha().equals(baseline.baselineSha()) || !candidate.commitSha().equals(candidate.candidateSha()))
            throw ApiProblem.conflict("Runs do not match one exact baseline/candidate PR commit snapshot.");
        if (baseline.status() != RunStatus.COMPLETE || candidate.status() != RunStatus.COMPLETE)
            throw ApiProblem.conflict("Only completed runs can be compared.");
        var b = repository.runBenchmark(baseline.id(), input.benchmarkCaseId());
        var c = repository.runBenchmark(candidate.id(), input.benchmarkCaseId());
        if (!b.benchmarkCase().id().equals(c.benchmarkCase().id()) || !b.benchmarkCase().unit().equals(c.benchmarkCase().unit())
                || b.benchmarkCase().direction() != c.benchmarkCase().direction())
            throw ApiProblem.conflict("Benchmark identities, units and directions must match.");
        var reasons = new ArrayList<String>();
        if (!baseline.environmentSha256().equals(candidate.environmentSha256())) reasons.add("ENVIRONMENT_MISMATCH");
        if (!b.configurationSha256().equals(c.configurationSha256())) reasons.add("CONFIGURATION_MISMATCH");
        if (!reasons.isEmpty()) return repository.insertComparison(baseline.repositoryId(), input, DescriptiveAnalysis.VERSION, "INCOMPATIBLE", null, reasons);
        Summary summary = DescriptiveAnalysis.compare(repository.allSamples(baseline.id(), input.benchmarkCaseId()),
                repository.allSamples(candidate.id(), input.benchmarkCaseId()), b.benchmarkCase().direction());
        String status = summary.baselineForks() < 10 || summary.candidateForks() < 10 ? "INSUFFICIENT_EVIDENCE" : "DESCRIPTIVE_ONLY";
        if (!baseline.provenance().equals("GITHUB_METADATA") || !candidate.provenance().equals("GITHUB_METADATA")) reasons.add("LOCAL_PROVENANCE_UNVERIFIED");
        else reasons.add("ARTIFACT_MEASUREMENTS_UNTRUSTED");
        reasons.add("INFERENTIAL_ANALYSIS_NOT_IMPLEMENTED");
        if (status.equals("INSUFFICIENT_EVIDENCE")) reasons.add("FEWER_THAN_10_INDEPENDENT_FORKS");
        return repository.insertComparison(baseline.repositoryId(), input, DescriptiveAnalysis.VERSION, status, summary, reasons);
    }
}
