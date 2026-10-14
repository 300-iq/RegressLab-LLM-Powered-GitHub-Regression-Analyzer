package dev.regresslab.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.regresslab.ai.AiDtos.*;
import dev.regresslab.api.ApiProblem;
import dev.regresslab.domain.ApiDtos.*;
import dev.regresslab.domain.ArtifactDtos.DataOrigin;
import dev.regresslab.persistence.AnalysisRepository;
import dev.regresslab.persistence.BenchmarkRepository;
import dev.regresslab.security.LocalIngestionSettings;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiEvidenceServiceTest {
    // Captured genuine measurements, used only as isolated read-only test input.
    final ObjectMapper mapper=new ObjectMapper().registerModule(new JavaTimeModule());
    final BenchmarkRepository benchmarks=mock(BenchmarkRepository.class);
    final AnalysisRepository analyses=mock(AnalysisRepository.class);
    final AiEvidenceService service=new AiEvidenceService(benchmarks,analyses,
            new LocalIngestionSettings(true,"test-only-token-000000000000000000000",1417739588,"300-iq/regresslab-java-demo",30),mapper);
    ComparisonDto comparison; Evidence evidence; RunDetail baseline,candidate; PullRequestDto pr;
    @BeforeEach void setup() throws Exception {
        JsonNode capture=mapper.readTree(Files.readAllBytes(Path.of("src/test/resources/fixtures/github-pr3-import.json")));
        JsonNode report=mapper.readTree(Files.readAllBytes(Path.of("src/test/resources/fixtures/github-pr3-report.json")));
        ((com.fasterxml.jackson.databind.node.ObjectNode)capture.path("candidate").path("run")).put("branch","fixture-candidate");
        comparison=mapper.treeToValue(capture.path("comparisons").get(0),ComparisonDto.class);
        baseline=mapper.treeToValue(capture.path("baseline"),RunDetail.class); candidate=mapper.treeToValue(capture.path("candidate"),RunDetail.class);
        pr=mapper.treeToValue(capture.path("pullRequest"),PullRequestDto.class);
        evidence=mapper.treeToValue(report.path("body").path("input").path("evidence"),Evidence.class);
        when(benchmarks.comparison(comparison.id())).thenReturn(comparison);
        when(analyses.get(evidence.analysis().id())).thenReturn(evidence.analysis());
        when(benchmarks.repository(comparison.repositoryId())).thenReturn(new RepositoryDto(comparison.repositoryId(),"300-iq/regresslab-java-demo","https://github.com/300-iq/regresslab-java-demo","300-iq","regresslab-java-demo",DataOrigin.REAL,"GITHUB_METADATA"));
        when(benchmarks.githubRepositoryId(comparison.repositoryId())).thenReturn(1417739588L);
        when(benchmarks.run(comparison.baselineRunId())).thenReturn(baseline.run()); when(benchmarks.run(comparison.candidateRunId())).thenReturn(candidate.run());
        when(benchmarks.pr(candidate.run().pullRequestId())).thenReturn(pr);
        when(benchmarks.runBenchmark(comparison.baselineRunId(),comparison.benchmarkCaseId())).thenReturn(baseline.benchmarks().getFirst());
        when(benchmarks.runBenchmark(comparison.candidateRunId(),comparison.benchmarkCaseId())).thenReturn(candidate.benchmarks().getFirst());
        when(benchmarks.allSamples(comparison.baselineRunId(),comparison.benchmarkCaseId())).thenReturn(evidence.baselineSamples());
        when(benchmarks.allSamples(comparison.candidateRunId(),comparison.benchmarkCaseId())).thenReturn(evidence.candidateSamples());
    }
    @Test void snapshotsGenuineMeasurementsConditionsAndExistingInconclusiveAnalysis() {
        var snapshot=service.snapshot(comparison.id(),evidence.analysis().id());
        assertThat(snapshot.evidence().analysis().result().classification().name()).isEqualTo("INCONCLUSIVE");
        assertThat(snapshot.evidence().baselineSamples()).hasSize(15);
        assertThat(snapshot.evidence().conditions().path("baselineConfiguration").path("warmupIterations").asInt()).isEqualTo(3);
    }
    @Test void rejectsWrongImmutableRepositoryIdentityEvenWithSameFullName() {
        when(benchmarks.githubRepositoryId(comparison.repositoryId())).thenReturn(2L);
        assertThatThrownBy(() -> service.snapshot(comparison.id(),evidence.analysis().id())).isInstanceOf(ApiProblem.class);
    }
    @Test void rejectsLocalUnverifiedRunAndHistoricalPrSnapshots() {
        var changed=mapper.valueToTree(candidate.run()); ((com.fasterxml.jackson.databind.node.ObjectNode)changed).put("provenance","LOCAL_UNVERIFIED");
        when(benchmarks.run(comparison.candidateRunId())).thenReturn(mapper.convertValue(changed,RunDto.class));
        assertThatThrownBy(() -> service.snapshot(comparison.id(),evidence.analysis().id())).isInstanceOf(ApiProblem.class);
        when(benchmarks.run(comparison.candidateRunId())).thenReturn(candidate.run());
        var moved=mapper.valueToTree(pr); ((com.fasterxml.jackson.databind.node.ObjectNode)moved).put("candidateSha",pr.baselineSha());
        when(benchmarks.pr(pr.id())).thenReturn(mapper.convertValue(moved,PullRequestDto.class));
        assertThatThrownBy(() -> service.snapshot(comparison.id(),evidence.analysis().id())).isInstanceOf(ApiProblem.class).hasMessageContaining("Historical");
    }
    @Test void refusesOversizedOrIncompleteSamples() {
        var b=baseline.benchmarks().getFirst();
        when(benchmarks.runBenchmark(comparison.baselineRunId(),comparison.benchmarkCaseId())).thenReturn(new RunBenchmarkDto(b.benchmarkCase(),b.parameters(),b.configuration(),b.configurationSha256(),101));
        assertThatThrownBy(() -> service.snapshot(comparison.id(),evidence.analysis().id())).isInstanceOf(ApiProblem.class).hasMessageContaining("100 observations");
        when(benchmarks.runBenchmark(comparison.baselineRunId(),comparison.benchmarkCaseId())).thenReturn(b);
        when(benchmarks.allSamples(comparison.baselineRunId(),comparison.benchmarkCaseId())).thenReturn(java.util.List.of());
        assertThatThrownBy(() -> service.snapshot(comparison.id(),evidence.analysis().id())).isInstanceOf(ApiProblem.class).hasMessageContaining("Complete raw");
    }
}
