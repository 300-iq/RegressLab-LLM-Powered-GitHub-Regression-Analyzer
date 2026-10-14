package dev.regresslab;

import dev.regresslab.analysis.ComparisonService;
import dev.regresslab.analysis.DescriptiveAnalysis;
import dev.regresslab.api.ApiProblem;
import dev.regresslab.domain.ApiDtos.*;
import dev.regresslab.domain.ArtifactDtos.*;
import dev.regresslab.persistence.BenchmarkRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ComparisonServiceTest {
    private final BenchmarkRepository repository = mock(BenchmarkRepository.class);
    private final ComparisonService service = new ComparisonService(repository);
    private final UUID repo = UUID.randomUUID(), pr = UUID.randomUUID(), base = UUID.randomUUID(), head = UUID.randomUUID(), caseId = UUID.randomUUID();
    private final ComparisonRequest input = new ComparisonRequest(base, head, caseId);
    private RunDto run(UUID id, boolean candidate, String env) {
        return new RunDto(id, repo, pr, 1, 1, "", candidate ? TestArtifacts.HEAD : TestArtifacts.BASE,
                TestArtifacts.BASE, TestArtifacts.HEAD, "main", Instant.EPOCH, null, env, RunStatus.COMPLETE, DataOrigin.DEMO,
                "LOCAL_UNVERIFIED", UUID.randomUUID(), "fixture", "0".repeat(64), Instant.EPOCH);
    }
    private void runs(String candidateEnv) {
        when(repository.run(base)).thenReturn(run(base, false, "env")); when(repository.run(head)).thenReturn(run(head, true, candidateEnv));
        var caseDto = new CaseDto(caseId, repo, "fixture", "average_time", "ns/op", Direction.LOWER);
        var benchmark = new RunBenchmarkDto(caseDto, null, null, "config", 50);
        when(repository.runBenchmark(base, caseId)).thenReturn(benchmark); when(repository.runBenchmark(head, caseId)).thenReturn(benchmark);
    }
    @Test void incompatibleEnvironmentNeverLoadsSamplesOrComputesAnEffect() {
        runs("other-env"); service.compute(input);
        verify(repository).insertComparison(repo, input, DescriptiveAnalysis.VERSION, "INCOMPATIBLE", null, List.of("ENVIRONMENT_MISMATCH"));
        verify(repository, never()).allSamples(any(), any());
    }
    @Test void aStableCaseCannotBeComparedToAnotherCaseOrUnit() {
        runs("env");
        var other = new RunBenchmarkDto(new CaseDto(UUID.randomUUID(), repo, "other", "throughput", "ops/s", Direction.HIGHER), null, null, "config", 50);
        when(repository.runBenchmark(head, caseId)).thenReturn(other);
        assertThatThrownBy(() -> service.compute(input)).isInstanceOf(ApiProblem.class);
        verify(repository, never()).allSamples(any(), any());
    }
    @Test void tenForksStillProduceOnlyDescriptiveEvidence() {
        runs("env");
        var samples = java.util.stream.IntStream.range(0, 10).mapToObj(i -> new SampleDto(i, 0, 10 + i)).toList();
        when(repository.allSamples(base, caseId)).thenReturn(samples); when(repository.allSamples(head, caseId)).thenReturn(samples);
        service.compute(input);
        verify(repository).insertComparison(eq(repo), eq(input), eq(DescriptiveAnalysis.VERSION), eq("DESCRIPTIVE_ONLY"), any(Summary.class),
                eq(List.of("LOCAL_PROVENANCE_UNVERIFIED", "INFERENTIAL_ANALYSIS_NOT_IMPLEMENTED")));
    }
}
