package dev.regresslab.analysis;

import dev.regresslab.analysis.AnalysisDtos.*;
import dev.regresslab.domain.ApiDtos.SampleDto;
import dev.regresslab.domain.ArtifactDtos.Direction;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static dev.regresslab.analysis.AnalysisDtos.Classification.*;
import static org.assertj.core.api.Assertions.*;

class BootstrapAnalysisTest {
    private final Policy policy = Policy.defaults(5, 1);
    private BootstrapAnalysis.Conditions conditions(int forks, int iterations, Direction direction) {
        return new BootstrapAnalysis.Conditions("test.case", direction == Direction.LOWER ? "average_time" : "throughput",
                direction == Direction.LOWER ? "ns/op" : "ops/s", direction, "env", "config", forks, iterations, 3);
    }
    private List<SampleDto> samples(double[] means, int iterations) {
        var result = new ArrayList<SampleDto>();
        for (int fork = 0; fork < means.length; fork++) for (int i = 0; i < iterations; i++) result.add(new SampleDto(fork, i, means[fork]));
        return result;
    }
    private double[] values(double factor) { return IntStream.range(0, 10).mapToDouble(i -> (98 + i * .4) * factor).toArray(); }
    private Result compare(double[] b, double[] c, Direction d) {
        return BootstrapAnalysis.compare(samples(b, 5), samples(c, 5), conditions(b.length, 5, d), conditions(c.length, 5, d), policy);
    }
    @Test void clearRegressionAndImprovementUseThePracticalBand() {
        var regression = compare(values(1), values(1.2), Direction.LOWER);
        assertThat(regression.classification()).isEqualTo(REGRESSION_INDICATED);
        assertThat(regression.relativeChangePercent()).isCloseTo(20, within(1e-10));
        assertThat(regression.interval().lowerPercent()).isGreaterThan(5);
        assertThat(compare(values(1), values(.8), Direction.LOWER).classification()).isEqualTo(IMPROVEMENT_INDICATED);
    }
    @Test void higherIsBetterReversesTheSignButUsesBaselineAsDenominator() {
        assertThat(compare(values(1), values(1.2), Direction.HIGHER).relativeChangePercent()).isCloseTo(-20, within(1e-10));
        assertThat(compare(values(1), values(1.2), Direction.HIGHER).classification()).isEqualTo(IMPROVEMENT_INDICATED);
        assertThat(compare(values(1), values(.8), Direction.HIGHER).classification()).isEqualTo(REGRESSION_INDICATED);
    }
    @Test void stableNearZeroChangeIsDetectedOnlyInsideTheWholeBand() {
        assertThat(compare(values(1), values(1.01), Direction.LOWER).classification()).isEqualTo(NO_MEANINGFUL_CHANGE_DETECTED);
        assertThat(BootstrapAnalysis.classify(-5, 5, 5)).isEqualTo(INCONCLUSIVE);
        assertThat(BootstrapAnalysis.classify(5, 8, 5)).isEqualTo(INCONCLUSIVE);
        assertThat(BootstrapAnalysis.classify(-8, -5, 5)).isEqualTo(INCONCLUSIVE);
    }
    @Test void noisyForksRemainInconclusiveEvenWithALargeObservedEffect() {
        var r = compare(new double[]{30, 50, 70, 80, 90, 110, 120, 140, 160, 190}, values(2), Direction.LOWER);
        assertThat(r.classification()).isEqualTo(INCONCLUSIVE);
        assertThat(r.reasons()).contains("HIGH_BETWEEN_FORK_VARIABILITY");
        assertThat(r.interval().exploratory()).isTrue();
    }
    @Test void aWideIntervalIsInconclusiveEvenWhenTheNoiseGuardPasses() {
        double[] noisy = {82, 86, 90, 94, 98, 102, 106, 110, 114, 118};
        var r = compare(noisy, Arrays.stream(noisy).map(v -> v * 1.03).toArray(), Direction.LOWER);
        assertThat(r.classification()).isEqualTo(INCONCLUSIVE);
        assertThat(r.reasons()).containsExactly("INTERVAL_CROSSES_PRACTICAL_BOUNDARY");
    }
    @Test void oneExtremeForkCannotBecomeAConfidentRegression() {
        var outlier = values(1.1); outlier[9] = 500;
        assertThat(compare(values(1), outlier, Direction.LOWER).classification()).isEqualTo(INCONCLUSIVE);
    }
    @Test void threeForksCannotBecomeEvidenceByRepeatingTheirIterations() {
        double[] b = {99, 100, 101}, c = {119, 120, 121};
        var small = compare(b, c, Direction.LOWER);
        var repeated = BootstrapAnalysis.compare(samples(b, 30), samples(c, 30), conditions(3, 30, Direction.LOWER), conditions(3, 30, Direction.LOWER), policy);
        assertThat(small.classification()).isEqualTo(INCONCLUSIVE);
        assertThat(repeated.classification()).isEqualTo(INCONCLUSIVE);
        assertThat(repeated.baseline().rawMeasurements().count()).isEqualTo(90);
        assertThat(repeated.baseline().forkMeans().count()).isEqualTo(3);
        assertThat(repeated.interval()).isEqualTo(small.interval());
    }
    @Test void zeroNonFiniteNegativeAndOutOfRangeValuesAreInvalid() {
        for (double value : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY, 1e16}) {
            var bad = new ArrayList<>(samples(values(1), 5)); bad.set(0, new SampleDto(0, 0, value));
            var r = BootstrapAnalysis.compare(bad, samples(values(1), 5), conditions(10, 5, Direction.LOWER), conditions(10, 5, Direction.LOWER), policy);
            assertThat(r.classification()).isEqualTo(INVALID_COMPARISON); assertThat(r.interval()).isNull();
        }
    }
    @Test void tinyFiniteValuesKeepVarianceAndUnrepresentableRatiosAreInvalid() {
        double[] tiny = Arrays.stream(values(1)).map(v -> v * 1e-200).toArray();
        var r = compare(tiny, tiny, Direction.LOWER);
        assertThat(r.baseline().forkMeans().standardDeviation()).isPositive();
        assertThat(r.classification()).isEqualTo(NO_MEANINGFUL_CHANGE_DETECTED);
        double[] smallest = new double[10]; Arrays.fill(smallest, 1e-320);
        assertThat(compare(smallest, values(1), Direction.LOWER).classification()).isEqualTo(INVALID_COMPARISON);
    }
    @Test void failedBootstrapNeverRetainsARelativeEffectOnAnInvalidComparison() {
        double[] b = {Double.MIN_NORMAL, Double.MIN_NORMAL, Double.MIN_NORMAL, Double.MIN_NORMAL, 100, 100, 100, 100, 100, 100};
        var r = compare(b, values(1.2), Direction.LOWER);
        assertThat(r.classification()).isEqualTo(INVALID_COMPARISON);
        assertThat(r.reasons()).contains("NON_FINITE_BOOTSTRAP_CHANGE");
        assertThat(r.relativeChangePercent()).isNull();
        assertThat(r.interval()).isNull();
    }
    @Test void subnormalMeasurementsCannotBypassTheNoiseGuardThroughRounding() {
        // Same exact integer multiples, but SD/mean quantization changes CV
        // from 0.2112568 to 0.1875 in v1, crossing the 0.20 noise guard.
        double[] normal = {13, 12, 20, 16, 11, 19, 16, 14, 14, 20};
        var noisy = compare(normal, values(1), Direction.LOWER);
        assertThat(noisy.reasons()).contains("HIGH_BETWEEN_FORK_VARIABILITY");
        double[] subnormal = Arrays.stream(normal).map(v -> v * Double.MIN_VALUE).toArray();
        var r = compare(subnormal, subnormal, Direction.LOWER);
        assertThat(r.classification()).isEqualTo(INVALID_COMPARISON);
        assertThat(r.reasons()).contains("UNSUPPORTED_SUBNORMAL_SCALE");
        assertThat(r.interval()).isNull(); assertThat(r.relativeChangePercent()).isNull();
    }
    @Test void incompatibleUnitsDirectionsAndIdentityNeverProduceAnEffect() {
        var b = conditions(10, 5, Direction.LOWER);
        for (var c : List.of(conditions(10, 5, Direction.HIGHER),
                new BootstrapAnalysis.Conditions("different", b.metricName(), b.unit(), b.direction(), b.environmentHash(), b.configurationHash(), 10, 5, 3),
                new BootstrapAnalysis.Conditions(b.identifier(), b.metricName(), "ms/op", b.direction(), b.environmentHash(), b.configurationHash(), 10, 5, 3))) {
            var r = BootstrapAnalysis.compare(samples(values(1), 5), samples(values(1.2), 5), b, c, policy);
            assertThat(r.classification()).isEqualTo(INVALID_COMPARISON);
            assertThat(r.relativeChangePercent()).isNull(); assertThat(r.interval()).isNull();
        }
    }
    @Test void differentEnvironmentOrConfigurationIsInvalidWithWarnings() {
        var b = conditions(10, 5, Direction.LOWER);
        for (var c : List.of(new BootstrapAnalysis.Conditions(b.identifier(), b.metricName(), b.unit(), b.direction(), "other", "config", 10, 5, 3),
                new BootstrapAnalysis.Conditions(b.identifier(), b.metricName(), b.unit(), b.direction(), "env", "other", 10, 5, 3))) {
            var r = BootstrapAnalysis.compare(samples(values(1), 5), samples(values(1.2), 5), b, c, policy);
            assertThat(r.classification()).isEqualTo(INVALID_COMPARISON);
            assertThat(r.environmentWarnings()).isNotEmpty(); assertThat(r.relativeChangePercent()).isNull();
        }
    }
    @Test void duplicateIncompleteAndOutOfRangeIndicesAreInvalid() {
        var full = samples(values(1), 5);
        for (var bad : List.of(full.subList(1, full.size()), changed(full, new SampleDto(1, 0, 100)), changed(full, new SampleDto(10, 0, 100))))
            assertThat(BootstrapAnalysis.compare(bad, full, conditions(10, 5, Direction.LOWER), conditions(10, 5, Direction.LOWER), policy).classification()).isEqualTo(INVALID_COMPARISON);
    }
    private List<SampleDto> changed(List<SampleDto> input, SampleDto sample) { var result = new ArrayList<>(input); result.set(0, sample); return result; }
    @Test void fixedSeedAndReorderedInputProduceExactlyTheSameResult() {
        var b = samples(values(1), 5); var c = samples(values(1.2), 5);
        var expected = BootstrapAnalysis.compare(b, c, conditions(10, 5, Direction.LOWER), conditions(10, 5, Direction.LOWER), policy);
        var shuffled = new ArrayList<>(b); Collections.shuffle(shuffled, new Random(42));
        assertThat(BootstrapAnalysis.compare(shuffled, c, conditions(10, 5, Direction.LOWER), conditions(10, 5, Direction.LOWER), policy)).isEqualTo(expected);
        assertThat(compare(values(1), values(1.2), Direction.LOWER)).isEqualTo(expected);
    }
    @Test void rawAndForkSummariesHaveExplicitCountsAndSampleDeviation() {
        var r = compare(values(1), values(1), Direction.LOWER);
        assertThat(r.baseline().rawMeasurements().count()).isEqualTo(50);
        assertThat(r.baseline().forkMeans().count()).isEqualTo(10);
        assertThat(r.baseline().forkMeans().mean()).isCloseTo(99.8, within(1e-12));
        assertThat(r.baseline().forkMeans().median()).isCloseTo(99.8, within(1e-12));
        assertThat(r.baseline().forkMeans().standardDeviation()).isCloseTo(Math.sqrt(13.2 / 9), within(1e-12));
    }
    @Test void degenerateForksAreNotTreatedAsPerfectConfidence() {
        var constant = new double[10]; Arrays.fill(constant, 100);
        var r = compare(constant, values(2), Direction.LOWER);
        assertThat(r.classification()).isEqualTo(INCONCLUSIVE); assertThat(r.reasons()).contains("LOW_FORK_DIVERSITY");
    }
    @Test void widerFamilyIntervalAndConfigurableThresholdAreRecorded() {
        var b = samples(values(1), 5); var c = samples(values(1.2), 5); var conditions = conditions(10, 5, Direction.LOWER);
        var single = BootstrapAnalysis.compare(b, c, conditions, conditions, policy);
        var multiple = BootstrapAnalysis.compare(b, c, conditions, conditions, Policy.defaults(5, 20));
        assertThat(multiple.interval().lowerPercent()).isLessThanOrEqualTo(single.interval().lowerPercent());
        assertThat(multiple.interval().upperPercent()).isGreaterThanOrEqualTo(single.interval().upperPercent());
        assertThat(multiple.interval().nominalConfidenceLevel()).isCloseTo(.9975, within(1e-12));
        assertThat(BootstrapAnalysis.compare(b, c, conditions, conditions, Policy.defaults(30, 1)).classification()).isEqualTo(NO_MEANINGFUL_CHANGE_DETECTED);
        assertThatThrownBy(() -> Policy.defaults(Double.NaN, 1)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void quantileInterpolationUsesDocumentedTypeSeven() {
        assertThat(BootstrapAnalysis.quantile(new double[]{0, 10, 20, 30}, .25)).isEqualTo(7.5);
    }
    private void requireCalibration(int covered, int falseFamilies) {
        // One-sided 99.5% binomial count cutoffs for this 200-trial scenario:
        // misses ~ Binomial(200, .025), false families ~ Binomial(200, .05).
        // These test tolerances do not validate coverage on other distributions.
        assertThat(covered).isGreaterThanOrEqualTo(189);
        assertThat(falseFamilies).isLessThanOrEqualTo(19);
    }
    @Test void calibrationGuardRejectsMaterialUndercoverageAndExcessFalseFamilies() {
        assertThatThrownBy(() -> requireCalibration(180, 0)).isInstanceOf(AssertionError.class);
        assertThatThrownBy(() -> requireCalibration(200, 20)).isInstanceOf(AssertionError.class);
    }
    @Test void deterministicSimulationChecksConditionalCoverageAndFamilyFalseIndications() {
        int trials = 200, covered = 0, falseFamilies = 0;
        var random = new Random(71991);
        for (int trial = 0; trial < trials; trial++) {
            boolean falseFamily = false;
            for (int caseIndex = 0; caseIndex < 2; caseIndex++) {
                double[] b = new double[10], c = new double[10];
                for (int i = 0; i < 10; i++) { b[i] = 100 * Math.exp(.05 * random.nextGaussian()); c[i] = 100 * Math.exp(.05 * random.nextGaussian()); }
                var r = BootstrapAnalysis.compare(samples(b, 5), samples(c, 5), conditions(10, 5, Direction.LOWER), conditions(10, 5, Direction.LOWER), Policy.defaults(.1, 2));
                if (caseIndex == 0 && r.interval().lowerPercent() <= 0 && r.interval().upperPercent() >= 0) covered++;
                if (r.classification() == REGRESSION_INDICATED || r.classification() == IMPROVEMENT_INDICATED) falseFamily = true;
            }
            if (falseFamily) falseFamilies++;
        }
        System.out.printf("Synthetic independent lognormal forks: coverage=%d/%d, false direction families=%d/%d%n", covered, trials, falseFamilies, trials);
        // A bounded scenario check, deliberately not a claim of universal nominal coverage.
        requireCalibration(covered, falseFamilies);
    }
}
