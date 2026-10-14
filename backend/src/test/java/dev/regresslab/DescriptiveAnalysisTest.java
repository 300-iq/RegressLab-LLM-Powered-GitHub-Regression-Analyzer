package dev.regresslab;

import dev.regresslab.analysis.DescriptiveAnalysis;
import dev.regresslab.api.ApiProblem;
import dev.regresslab.domain.ApiDtos.SampleDto;
import dev.regresslab.domain.ArtifactDtos.Direction;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DescriptiveAnalysisTest {
    @Test void usesIndependentForkMeansInsteadOfPoolingIterations() {
        var stats = DescriptiveAnalysis.summarize(List.of(new SampleDto(0, 0, 10), new SampleDto(0, 1, 10), new SampleDto(1, 0, 20)));
        assertThat(stats.forks()).isEqualTo(2);
        assertThat(stats.samples()).isEqualTo(3);
        assertThat(stats.mean()).isEqualTo(15);
        assertThat(stats.median()).isEqualTo(15);
        assertThat(stats.stdDev()).isCloseTo(Math.sqrt(50), within(1e-10));
    }
    @Test void respectsMetricDirectionAndIsDeterministic() {
        var baseline = List.of(new SampleDto(0, 0, 100));
        var candidate = List.of(new SampleDto(0, 0, 120));
        var latency = DescriptiveAnalysis.compare(baseline, candidate, Direction.LOWER);
        assertThat(latency.degradationPercent()).isCloseTo(20, within(1e-10));
        assertThat(DescriptiveAnalysis.compare(baseline, candidate, Direction.HIGHER).degradationPercent()).isCloseTo(-20, within(1e-10));
        assertThat(DescriptiveAnalysis.compare(baseline, candidate, Direction.LOWER)).isEqualTo(latency);
    }
    @Test void handlesIdenticalAndZeroVarianceData() {
        var samples = List.of(new SampleDto(0, 0, 1), new SampleDto(1, 0, 1), new SampleDto(2, 0, 1));
        var result = DescriptiveAnalysis.compare(samples, samples, Direction.LOWER);
        assertThat(result.degradationPercent()).isZero(); assertThat(result.baselineCv()).isZero();
    }
    @Test void rejectsMissingSamplesAndUnrepresentableRatios() {
        assertThatThrownBy(() -> DescriptiveAnalysis.summarize(List.of())).isInstanceOf(ApiProblem.class);
        assertThatThrownBy(() -> DescriptiveAnalysis.compare(List.of(new SampleDto(0, 0, 1e-320)), List.of(new SampleDto(0, 0, 1e15)), Direction.LOWER))
                .isInstanceOf(ApiProblem.class);
    }
    @Test void preservesVariationForSmallPositiveValuesAcceptedByTheContract() {
        var stats = DescriptiveAnalysis.summarize(List.of(new SampleDto(0, 0, 1e-200),
                new SampleDto(1, 0, 2e-200), new SampleDto(2, 0, 3e-200)));
        assertThat(stats.stdDev() / 1e-200).isCloseTo(1, within(1e-12));
        assertThat(stats.cv()).isCloseTo(0.5, within(1e-12));
    }
}
