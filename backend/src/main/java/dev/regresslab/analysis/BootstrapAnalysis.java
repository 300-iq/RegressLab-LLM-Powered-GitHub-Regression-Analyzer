package dev.regresslab.analysis;

import dev.regresslab.analysis.AnalysisDtos.*;
import dev.regresslab.domain.ApiDtos.SampleDto;
import dev.regresslab.domain.ArtifactDtos.Direction;
import java.util.*;
import static dev.regresslab.analysis.AnalysisDtos.Classification.*;

/** Conditional fork-level inference; iterations are never resampled as independent units. */
public final class BootstrapAnalysis {
    public static final String VERSION = "fork-bootstrap-v2";
    public record Conditions(String identifier, String metricName, String unit, Direction direction,
                             String environmentHash, String configurationHash, int forks,
                             int iterations, int warmups) {}
    private static final List<String> LIMITATIONS = List.of(
            "Iterations within a JVM fork are dependent. The bootstrap resamples whole fork means, not individual iterations.",
            "The interval assumes exchangeable, independent forks within each arm and independent arms. Shared runner noise, drift and cross-fork dependence can violate these assumptions.",
            "Matching environment metadata does not establish equivalent CPU contention, scheduling, thermal state or completed warmup convergence.",
            "This is a percentile bootstrap with a nominal, not guaranteed, coverage level; small samples, quantization and unobserved noise can make it misleading.",
            "Bonferroni adjustment covers the recorded run-pair case family only, subject to interval validity. Repeated PRs, reruns and post-hoc threshold selection are not controlled.",
            "An indication is conditional evidence for this workload and runner, not proof of causation, measurement honesty or a universal performance change.");
    private BootstrapAnalysis() {}

    public static Result compare(List<SampleDto> baseline, List<SampleDto> candidate,
                                 Conditions b, Conditions c, Policy policy) {
        // Only versioned supported policies may reach persistence or the CPU-bound resampler.
        if (!policy.equals(Policy.defaults(policy.practicalThresholdPercent(), policy.familySize())))
            throw new IllegalArgumentException("Unsupported policy for " + VERSION);
        ArmSummary bs, cs;
        try { bs = summarize(baseline, b); cs = summarize(candidate, c); }
        catch (IllegalArgumentException invalid) {
            return result(b, null, null, null, null, INVALID_COMPARISON, List.of(invalid.getMessage()), List.of());
        }
        var incompatible = new ArrayList<String>();
        var warnings = new ArrayList<String>();
        if (!b.identifier().equals(c.identifier())) incompatible.add("BENCHMARK_IDENTIFIER_MISMATCH");
        if (!b.unit().equals(c.unit()) || !b.metricName().equals(c.metricName()) || b.direction() != c.direction())
            incompatible.add("METRIC_UNIT_OR_DIRECTION_MISMATCH");
        if (!b.environmentHash().equals(c.environmentHash())) {
            incompatible.add("ENVIRONMENT_MISMATCH"); warnings.add("Recorded environment hashes differ; no relative effect or interval is calculated.");
        }
        if (!b.configurationHash().equals(c.configurationHash()) || b.forks() != c.forks()
                || b.iterations() != c.iterations() || b.warmups() != c.warmups()) {
            incompatible.add("CONFIGURATION_MISMATCH"); warnings.add("Benchmark parameters or JMH configuration differ; the runs are not comparable.");
        }
        if (!incompatible.isEmpty()) return result(b, bs, cs, null, null, INVALID_COMPARISON, incompatible, warnings);
        warnings.add("Recorded environments match; unrecorded runner contention and drift remain possible.");
        double effect = change(bs.forkMeans().median(), cs.forkMeans().median(), b.direction());
        if (!Double.isFinite(effect)) return result(b, bs, cs, null, null, INVALID_COMPARISON, List.of("NON_FINITE_RELATIVE_CHANGE"), warnings);
        var reasons = new ArrayList<String>();
        if (b.forks() < policy.minimumForksPerArm() || c.forks() < policy.minimumForksPerArm()) reasons.add("TOO_FEW_FORKS");
        if (b.iterations() < policy.minimumIterationsPerFork() || c.iterations() < policy.minimumIterationsPerFork()) reasons.add("TOO_FEW_ITERATIONS_PER_FORK");
        if (bs.orderedForkMeans().stream().distinct().count() < policy.minimumDistinctForkMeans()
                || cs.orderedForkMeans().stream().distinct().count() < policy.minimumDistinctForkMeans()) reasons.add("LOW_FORK_DIVERSITY");
        if (bs.forkMeans().coefficientOfVariation() > policy.maximumForkCv()
                || cs.forkMeans().coefficientOfVariation() > policy.maximumForkCv()) reasons.add("HIGH_BETWEEN_FORK_VARIABILITY");
        Interval interval = bootstrap(bs, cs, b.direction(), policy, !reasons.isEmpty());
        if (interval == null) return result(b, bs, cs, null, null, INVALID_COMPARISON, List.of("NON_FINITE_BOOTSTRAP_CHANGE"), warnings);
        Classification classification = reasons.isEmpty() ? classify(interval.lowerPercent(), interval.upperPercent(), policy.practicalThresholdPercent()) : INCONCLUSIVE;
        // A single fork must not determine which side of the practical band is selected.
        if (classification != INCONCLUSIVE && !stableUnderDeletion(bs, cs, b.direction(), policy.practicalThresholdPercent(), classification)) {
            classification = INCONCLUSIVE; reasons.add("SINGLE_FORK_SENSITIVE");
            interval = new Interval(interval.lowerPercent(), interval.upperPercent(), interval.nominalConfidenceLevel(), interval.method(),
                    interval.statistic(), interval.resamplingUnit(), interval.quantileMethod(), interval.resamples(), interval.seed(), true);
        }
        if (classification == INCONCLUSIVE && reasons.isEmpty()) reasons.add("INTERVAL_CROSSES_PRACTICAL_BOUNDARY");
        return result(b, bs, cs, effect, interval, classification, reasons, warnings);
    }

    static Classification classify(double lower, double upper, double threshold) {
        if (lower > threshold) return REGRESSION_INDICATED;
        if (upper < -threshold) return IMPROVEMENT_INDICATED;
        if (lower > -threshold && upper < threshold) return NO_MEANINGFUL_CHANGE_DETECTED;
        return INCONCLUSIVE;
    }

    private static ArmSummary summarize(List<SampleDto> samples, Conditions conditions) {
        if (conditions == null || conditions.identifier() == null || conditions.environmentHash() == null
                || conditions.configurationHash() == null || conditions.forks() < 3 || conditions.forks() > 10
                || conditions.iterations() < 5 || conditions.iterations() > 30 || conditions.warmups() < 1)
            throw new IllegalArgumentException("INVALID_CONFIGURATION");
        boolean latency = "average_time".equals(conditions.metricName()) && "ns/op".equals(conditions.unit()) && conditions.direction() == Direction.LOWER;
        boolean throughput = "throughput".equals(conditions.metricName()) && "ops/s".equals(conditions.unit()) && conditions.direction() == Direction.HIGHER;
        if (!latency && !throughput) throw new IllegalArgumentException("INVALID_METRIC_UNIT_OR_DIRECTION");
        if (samples == null || samples.size() != conditions.forks() * conditions.iterations()) throw new IllegalArgumentException("INCOMPLETE_SAMPLE_GRID");
        double[][] forks = new double[conditions.forks()][conditions.iterations()];
        double[] raw = new double[samples.size()];
        int index = 0;
        for (var s : samples) {
            if (s == null || !Double.isFinite(s.value()) || s.value() <= 0 || s.value() > 1e15) throw new IllegalArgumentException("INVALID_MEASUREMENT_VALUE");
            // At subnormal scales unit-valued means/SDs lose enough precision to
            // cross the CV gate. Keep stored observations, but refuse inference.
            if (s.value() < Double.MIN_NORMAL) throw new IllegalArgumentException("UNSUPPORTED_SUBNORMAL_SCALE");
            if (s.forkIndex() < 0 || s.forkIndex() >= forks.length || s.iterationIndex() < 0 || s.iterationIndex() >= conditions.iterations()
                    || forks[s.forkIndex()][s.iterationIndex()] != 0) throw new IllegalArgumentException("INVALID_OR_DUPLICATE_SAMPLE_INDEX");
            forks[s.forkIndex()][s.iterationIndex()] = s.value(); raw[index++] = s.value();
        }
        double[] means = Arrays.stream(forks).mapToDouble(a -> Arrays.stream(a).average().orElseThrow()).toArray();
        // Deterministic reductions and resampling order, independent of SQL/input ordering.
        Arrays.sort(raw);
        return new ArmSummary(conditions.unit(), conditions.direction(), stats(raw), stats(means), Arrays.stream(means).boxed().toList());
    }

    static Statistics stats(double[] values) {
        double[] a = values.clone(); Arrays.sort(a);
        double mean = Arrays.stream(a).average().orElseThrow();
        double scale = Arrays.stream(a).map(v -> Math.abs(v - mean)).max().orElse(0);
        Double sd = a.length < 2 ? null : scale == 0 ? 0.0 : scale * Math.sqrt(Arrays.stream(a)
                .map(v -> Math.pow((v - mean) / scale, 2)).sum() / (a.length - 1));
        return new Statistics(a.length, mean, median(a), sd, sd == null ? null : sd / mean);
    }
    private static double median(double[] sorted) {
        int n = sorted.length;
        return n % 2 == 0 ? sorted[n / 2 - 1] + (sorted[n / 2] - sorted[n / 2 - 1]) / 2 : sorted[n / 2];
    }
    private static double change(double baseline, double candidate, Direction direction) {
        double ratio = candidate / baseline;
        return ratio > 0 && Double.isFinite(ratio) ? (direction == Direction.LOWER ? ratio - 1 : 1 - ratio) * 100 : Double.NaN;
    }
    private static Interval bootstrap(ArmSummary b, ArmSummary c, Direction direction, Policy policy, boolean exploratory) {
        var random = new Random(policy.seed());
        double[] estimates = new double[policy.resamples()];
        for (int i = 0; i < estimates.length; i++) {
            estimates[i] = change(resampledMedian(b.orderedForkMeans(), random), resampledMedian(c.orderedForkMeans(), random), direction);
            if (!Double.isFinite(estimates[i])) return null;
        }
        Arrays.sort(estimates);
        double tail = (1 - policy.familyConfidenceLevel()) / (2 * policy.familySize());
        return new Interval(quantile(estimates, tail), quantile(estimates, 1 - tail), 1 - 2 * tail,
                "independent-arm-fork-percentile-bootstrap", "direction-adjusted-relative-median-of-fork-means-percent",
                "JVM_FORK_MEAN", "linear-interpolation-type-7", policy.resamples(), policy.seed(), exploratory);
    }
    private static double resampledMedian(List<Double> means, Random random) {
        double[] draw = new double[means.size()];
        for (int i = 0; i < draw.length; i++) draw[i] = means.get(random.nextInt(draw.length));
        Arrays.sort(draw); return median(draw);
    }
    static double quantile(double[] sorted, double probability) {
        double location = probability * (sorted.length - 1);
        int lo = (int) Math.floor(location), hi = (int) Math.ceil(location);
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (location - lo);
    }
    private static boolean stableUnderDeletion(ArmSummary b, ArmSummary c, Direction d, double threshold, Classification target) {
        for (int i = 0; i < b.orderedForkMeans().size(); i++)
            if (classifyPoint(change(medianWithout(b.orderedForkMeans(), i), c.forkMeans().median(), d), threshold) != target) return false;
        for (int i = 0; i < c.orderedForkMeans().size(); i++)
            if (classifyPoint(change(b.forkMeans().median(), medianWithout(c.orderedForkMeans(), i), d), threshold) != target) return false;
        return true;
    }
    private static double medianWithout(List<Double> means, int excluded) {
        double[] values = java.util.stream.IntStream.range(0, means.size()).filter(i -> i != excluded).mapToDouble(means::get).sorted().toArray();
        return median(values);
    }
    private static Classification classifyPoint(double effect, double threshold) { return classify(effect, effect, threshold); }
    private static Result result(Conditions b, ArmSummary bs, ArmSummary cs, Double effect, Interval interval,
                                 Classification classification, List<String> reasons, List<String> warnings) {
        String explanation = switch (classification) {
            case REGRESSION_INDICATED -> "The conditional interval lies entirely above the practical degradation threshold, and adequacy/noise checks pass. A regression is indicated for these recorded conditions.";
            case IMPROVEMENT_INDICATED -> "The conditional interval lies entirely below the negative practical threshold, and adequacy/noise checks pass. An improvement is indicated for these recorded conditions.";
            case NO_MEANINGFUL_CHANGE_DETECTED -> "The conditional interval lies strictly inside the practical change band, and adequacy/noise checks pass. Changes smaller than the threshold remain possible.";
            case INCONCLUSIVE -> "The evidence cannot resolve a practical change. " + describeReasons(reasons)
                    + " Any interval is conditional and must not override adequacy or noise warnings.";
            case INVALID_COMPARISON -> "No comparable relative effect is available. " + describeReasons(reasons)
                    + " Check the measurements and benchmark conditions.";
        };
        return new Result(b == null ? null : b.identifier(), b == null ? null : b.unit(), b == null ? null : b.direction(),
                bs, cs, effect, interval, classification, explanation, List.copyOf(reasons), List.copyOf(warnings), LIMITATIONS);
    }
    private static String describeReasons(List<String> reasons) {
        return String.join(" ", reasons.stream().map(reason -> switch (reason) {
            case "TOO_FEW_FORKS" -> "At least 10 JVM forks per arm are required for an indication.";
            case "TOO_FEW_ITERATIONS_PER_FORK" -> "At least five measured iterations per fork are required.";
            case "LOW_FORK_DIVERSITY" -> "Too few distinct fork means are observed to support a stable interval.";
            case "HIGH_BETWEEN_FORK_VARIABILITY" -> "Between-fork variability exceeds the 20% coefficient-of-variation guard.";
            case "SINGLE_FORK_SENSITIVE" -> "Removing one fork changes the practical interpretation.";
            case "INTERVAL_CROSSES_PRACTICAL_BOUNDARY" -> "The interval crosses a practical change boundary.";
            case "ENVIRONMENT_MISMATCH" -> "Recorded execution environments differ.";
            case "CONFIGURATION_MISMATCH" -> "Benchmark parameters or measurement configuration differ.";
            case "BENCHMARK_IDENTIFIER_MISMATCH" -> "Benchmark case identifiers differ.";
            case "METRIC_UNIT_OR_DIRECTION_MISMATCH", "INVALID_METRIC_UNIT_OR_DIRECTION" -> "Metrics, units or performance directions are incompatible.";
            case "INVALID_MEASUREMENT_VALUE" -> "Measurements must be finite, positive and within the supported range.";
            case "UNSUPPORTED_SUBNORMAL_SCALE" -> "Measurements are below the supported numerical scale for reliable summaries and noise checks.";
            case "INVALID_CONFIGURATION" -> "The recorded fork, iteration or warmup configuration is unsupported.";
            case "INCOMPLETE_SAMPLE_GRID", "INVALID_OR_DUPLICATE_SAMPLE_INDEX" -> "Measurements are missing, duplicated or incorrectly indexed.";
            case "NON_FINITE_RELATIVE_CHANGE", "NON_FINITE_BOOTSTRAP_CHANGE" -> "The measurement scale cannot produce a finite relative change.";
            default -> "An analysis validity check did not pass.";
        }).toList());
    }
}
