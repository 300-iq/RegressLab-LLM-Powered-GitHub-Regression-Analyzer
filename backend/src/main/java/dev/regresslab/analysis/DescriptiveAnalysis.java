package dev.regresslab.analysis;

import dev.regresslab.api.ApiProblem;
import dev.regresslab.domain.ApiDtos.SampleDto;
import dev.regresslab.domain.ApiDtos.Summary;
import dev.regresslab.domain.ArtifactDtos.Direction;
import java.util.List;
import java.util.stream.Collectors;

public final class DescriptiveAnalysis {
    public static final String VERSION = "fork-descriptive-v2";
    private DescriptiveAnalysis() {}
    public record Stats(int forks, int samples, double mean, double median, double stdDev, double cv) {}
    public static Stats summarize(List<SampleDto> samples) {
        if (samples.isEmpty()) throw ApiProblem.invalid("No measured samples are available.");
        double[] means = samples.stream().collect(Collectors.groupingBy(SampleDto::forkIndex,
                Collectors.averagingDouble(SampleDto::value))).values().stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double mean = java.util.Arrays.stream(means).average().orElseThrow();
        int n = means.length;
        double median = n % 2 == 0 ? (means[n / 2 - 1] + means[n / 2]) / 2 : means[n / 2];
        // Squaring tiny but accepted measurements directly can underflow to zero.
        // Scale deviations first, then restore the original unit after the square root.
        double scale = java.util.Arrays.stream(means).map(v -> Math.abs(v - mean)).max().orElse(0);
        double stdDev = n > 1 && scale > 0 ? scale * Math.sqrt(java.util.Arrays.stream(means)
                .map(v -> { double normalized = (v - mean) / scale; return normalized * normalized; })
                .sum() / (n - 1)) : 0;
        return new Stats(n, samples.size(), mean, median, stdDev, stdDev / mean);
    }
    public static Summary compare(List<SampleDto> baseline, List<SampleDto> candidate, Direction direction) {
        Stats b = summarize(baseline); Stats c = summarize(candidate);
        double ratio = c.mean() / b.mean();
        double degradation = (direction == Direction.LOWER ? ratio - 1 : 1 - ratio) * 100;
        if (!Double.isFinite(ratio) || ratio <= 0 || !Double.isFinite(degradation))
            throw ApiProblem.invalid("Measurement scale cannot produce a finite comparison ratio.");
        return new Summary(b.forks(), c.forks(), b.samples(), c.samples(), b.mean(), c.mean(), b.median(), c.median(),
                b.stdDev(), c.stdDev(), b.cv(), c.cv(), ratio, degradation);
    }
}
