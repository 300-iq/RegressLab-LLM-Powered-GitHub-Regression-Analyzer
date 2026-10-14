package dev.regresslab.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.regresslab.analysis.AnalysisDtos.*;
import dev.regresslab.domain.ApiDtos.*;
import dev.regresslab.domain.ArtifactDtos.RunStatus;
import dev.regresslab.ingestion.Digests;
import dev.regresslab.persistence.AnalysisRepository;
import dev.regresslab.persistence.BenchmarkRepository;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ComparisonAnalysisService {
    private final BenchmarkRepository benchmarks;
    private final AnalysisRepository analyses;
    private final ObjectMapper mapper;
    public ComparisonAnalysisService(BenchmarkRepository benchmarks, AnalysisRepository analyses, ObjectMapper mapper) {
        this.benchmarks = benchmarks; this.analyses = analyses; this.mapper = mapper;
    }
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AnalysisDto compute(UUID comparisonId, Request request) {
        benchmarks.lockWrites();
        var comparison = benchmarks.comparison(comparisonId);
        var baseline = benchmarks.run(comparison.baselineRunId()); var candidate = benchmarks.run(comparison.candidateRunId());
        var b = benchmarks.runBenchmark(baseline.id(), comparison.benchmarkCaseId());
        var c = benchmarks.runBenchmark(candidate.id(), comparison.benchmarkCaseId());
        // Correct for all cases present in both runs, not just the chosen case.
        var caseIds = new HashSet<UUID>();
        benchmarks.runBenchmarks(baseline.id()).forEach(item -> caseIds.add(item.benchmarkCase().id()));
        var candidateCaseIds = new HashSet<UUID>();
        benchmarks.runBenchmarks(candidate.id()).forEach(item -> candidateCaseIds.add(item.benchmarkCase().id()));
        caseIds.retainAll(candidateCaseIds);
        var policy = Policy.defaults(request.practicalThresholdPercent() == null ? 5 : request.practicalThresholdPercent(), caseIds.size());
        String policyHash = Digests.canonical(mapper, policy);
        var bs = benchmarks.allSamples(baseline.id(), comparison.benchmarkCaseId());
        var cs = benchmarks.allSamples(candidate.id(), comparison.benchmarkCaseId());
        var bc = conditions(baseline, b); var cc = conditions(candidate, c);
        String provenance = baseline.provenance().equals("GITHUB_METADATA") && candidate.provenance().equals("GITHUB_METADATA")
                ? "GITHUB_METADATA" : "LOCAL_UNVERIFIED";
        String inputHash = Digests.canonical(mapper, Map.of("baselineArtifactSha256", baseline.artifactSha256(),
                "candidateArtifactSha256", candidate.artifactSha256(), "baselineConditions", bc, "candidateConditions", cc,
                "baselineSamples", bs, "candidateSamples", cs, "provenance", provenance, "dataOrigin", comparison.dataOrigin()));
        var existing = analyses.find(comparisonId, BootstrapAnalysis.VERSION, policyHash, inputHash);
        if (existing != null) return existing;
        var result = BootstrapAnalysis.compare(bs, cs, bc, cc, policy);
        var warnings = new ArrayList<>(result.environmentWarnings());
        warnings.add(baseline.provenance().equals("GITHUB_METADATA") && candidate.provenance().equals("GITHUB_METADATA")
                ? "GitHub artifact origin is checked; measurements remain untrusted and independence is not attested."
                : "Local artifact provenance and execution ordering are unverified; interpretation is exploratory.");
        // Missing execution-order evidence must not silently become inferential evidence.
        if (!baseline.provenance().equals("GITHUB_METADATA") || !candidate.provenance().equals("GITHUB_METADATA")) {
            if (result.classification() != Classification.INVALID_COMPARISON) {
                var reasons = new ArrayList<>(result.reasons()); reasons.add("EXECUTION_PROTOCOL_UNVERIFIED");
                var interval = result.interval();
                if (interval != null) interval = new Interval(interval.lowerPercent(), interval.upperPercent(), interval.nominalConfidenceLevel(),
                        interval.method(), interval.statistic(), interval.resamplingUnit(), interval.quantileMethod(), interval.resamples(), interval.seed(), true);
                result = new Result(result.benchmarkIdentifier(), result.unit(), result.direction(), result.baseline(), result.candidate(),
                        result.relativeChangePercent(), interval, Classification.INCONCLUSIVE,
                        "Execution protocol is unverified. Descriptive effects and any bootstrap interval are exploratory; no directional classification is supported.",
                        List.copyOf(reasons), result.environmentWarnings(), result.limitations());
            }
        }
        result = new Result(result.benchmarkIdentifier(), result.unit(), result.direction(), result.baseline(), result.candidate(),
                result.relativeChangePercent(), result.interval(), result.classification(), result.explanation(), result.reasons(), List.copyOf(warnings), result.limitations());
        return analyses.insert(comparisonId, BootstrapAnalysis.VERSION, policyHash, inputHash, policy, result, comparison.dataOrigin(), provenance);
    }
    private BootstrapAnalysis.Conditions conditions(RunDto run, RunBenchmarkDto benchmark) {
        var config = benchmark.configuration(); var identity = benchmark.benchmarkCase();
        return new BootstrapAnalysis.Conditions(identity.identifier(), identity.metricName(), identity.unit(), identity.direction(),
                run.environmentSha256(), benchmark.configurationSha256(), run.status() == RunStatus.COMPLETE ? config.path("forkCount").asInt() : 0,
                config.path("measurementIterations").asInt(), config.path("warmupIterations").asInt());
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AnalysisDto get(UUID id) { return analyses.get(id); }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<AnalysisDto> list(UUID comparisonId, int page, int size) {
        benchmarks.comparison(comparisonId);
        return analyses.list(comparisonId, page, size);
    }
}
