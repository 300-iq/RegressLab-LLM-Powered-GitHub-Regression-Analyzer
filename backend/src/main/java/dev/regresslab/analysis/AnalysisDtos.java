package dev.regresslab.analysis;

import dev.regresslab.domain.ArtifactDtos.Direction;
import dev.regresslab.domain.ArtifactDtos.DataOrigin;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AnalysisDtos {
    private AnalysisDtos() {}
    public record Request(@DecimalMin("0.1") @DecimalMax("50.0") Double practicalThresholdPercent) {}
    public enum Classification {
        REGRESSION_INDICATED, IMPROVEMENT_INDICATED, NO_MEANINGFUL_CHANGE_DETECTED, INCONCLUSIVE, INVALID_COMPARISON
    }
    public record Policy(double practicalThresholdPercent, int minimumForksPerArm,
                         int minimumIterationsPerFork, int minimumDistinctForkMeans,
                         double maximumForkCv, int resamples, long seed,
                         double familyConfidenceLevel, int familySize) {
        public static Policy defaults(double threshold, int familySize) {
            if (!Double.isFinite(threshold) || threshold < .1 || threshold > 50 || familySize < 1 || familySize > 20)
                throw new IllegalArgumentException("Invalid analysis policy");
            return new Policy(threshold, 10, 5, 5, .20, 10_000, 20261013L, .95, familySize);
        }
    }
    public record Statistics(int count, double mean, double median, Double standardDeviation, Double coefficientOfVariation) {}
    public record ArmSummary(String unit, Direction direction, Statistics rawMeasurements,
                             Statistics forkMeans, List<Double> orderedForkMeans) {}
    public record Interval(double lowerPercent, double upperPercent, double nominalConfidenceLevel,
                           String method, String statistic, String resamplingUnit, String quantileMethod,
                           int resamples, long seed, boolean exploratory) {}
    public record Result(String benchmarkIdentifier, String unit, Direction direction,
                         ArmSummary baseline, ArmSummary candidate, Double relativeChangePercent,
                         Interval interval, Classification classification, String explanation,
                         List<String> reasons, List<String> environmentWarnings, List<String> limitations) {}
    public record AnalysisDto(UUID id, UUID comparisonId, String analysisVersion, String policySha256,
                              String inputSha256, Policy policy, Result result, DataOrigin dataOrigin,
                              String provenance, Instant createdAt) {}
}
