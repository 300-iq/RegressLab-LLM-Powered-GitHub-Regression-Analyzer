package dev.regresslab.domain;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class ArtifactDtos {
    private ArtifactDtos() {}
    public static final String SHA = "^[0-9a-f]{40}$";
    public enum DataOrigin { REAL, DEMO }
    public enum Direction { LOWER, HIGHER }
    public enum RunStatus { COMPLETE, FAILED }
    public enum PrState { OPEN, CLOSED, MERGED }

    public record Artifact(
            @Min(2) @Max(2) int schemaVersion,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$") String artifactId,
            @NotNull DataOrigin dataOrigin,
            @NotNull @Valid RepositoryInput repository,
            @NotNull @Valid PullRequestInput pullRequest,
            @NotNull @Valid RunInput run,
            @NotNull @Size(max = 20) List<@NotNull @Valid CaseInput> benchmarks) {}

    public record RepositoryInput(
            @NotNull @Positive @Max(9007199254740991L) Long githubId,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9-]{0,38}$") String owner,
            @NotBlank @Pattern(regexp = "^[A-Za-z0-9_.-]{1,100}$") String name) {
        public String fullName() { return owner + "/" + name; }
    }

    public record PullRequestInput(
            @Positive int number, @NotBlank @Size(max = 500) String title,
            @NotNull PrState state, @NotNull @Pattern(regexp = SHA) String baselineSha,
            @NotNull @Pattern(regexp = SHA) String candidateSha) {}

    public record RunInput(
            @NotNull @Positive @Max(9007199254740991L) Long workflowRunId,
            @Positive int runAttempt, @NotNull @Pattern(regexp = SHA) String commitSha,
            @NotBlank @Size(max = 200) String branch, @NotNull Instant measuredAt,
            @NotNull RunStatus status, @NotNull @Valid Environment environment) {}

    public record Environment(
            @NotBlank @Size(max = 200) String runner, @NotBlank @Size(max = 200) String os,
            @NotNull @Pattern(regexp = "x86_64|aarch64") String architecture,
            @NotBlank @Size(max = 200) String cpu, @NotNull @Pattern(regexp = "21(?:[.+_-].*)?") @Size(max = 200) String javaVersion,
            @NotNull @Pattern(regexp = "1\\.37") String jmhVersion) {}

    public record CaseInput(
            @NotBlank @Size(max = 200) @Pattern(regexp = "^[A-Za-z0-9_.$-]+$") String identifier,
            @NotNull @Pattern(regexp = "average_time|throughput") String metricName,
            @NotNull @Pattern(regexp = "ns/op|ops/s") String unit, @NotNull Direction direction,
            @NotNull @Size(max = 10) Map<@Pattern(regexp = "^[A-Za-z0-9_]{1,50}$") String, @NotNull @Size(max = 100) String> parameters,
            @NotNull @Valid Configuration configuration,
            @NotNull @Size(min = 15, max = 300) List<@NotNull @Valid SampleInput> samples) {}

    public record Configuration(
            @Min(1) @Max(30) int warmupIterations, @Min(5) @Max(30) int measurementIterations,
            @Min(3) @Max(10) int forkCount, @Min(100) @Max(60000) int warmupTimeMs,
            @Min(100) @Max(60000) int measurementTimeMs,
            @NotNull @Size(max = 32) List<@NotNull @Size(max = 200) String> jvmArgs) {}

    public record SampleInput(@Min(0) @Max(9) int forkIndex, @Min(0) @Max(29) int iterationIndex,
                              @NotNull Double value) {}
}
