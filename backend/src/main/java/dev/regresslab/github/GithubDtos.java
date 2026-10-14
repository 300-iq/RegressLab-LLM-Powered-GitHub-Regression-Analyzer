package dev.regresslab.github;
import dev.regresslab.domain.ApiDtos.ImportResult;
import dev.regresslab.domain.ArtifactDtos.Artifact;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class GithubDtos {
    private GithubDtos() {}
    public record ImportRequest(@NotNull @Positive @Max(9007199254740991L) Long workflowRunId,
                                @NotNull @Positive @Max(9007199254740991L) Long baselineArtifactId,
                                @NotNull @Positive @Max(9007199254740991L) Long candidateArtifactId,
                                @NotNull @Size(min=1,max=512) List<@NotBlank @Size(max=2000) String> baselineArchiveChunks,
                                @NotNull @Size(min=1,max=512) List<@NotBlank @Size(max=2000) String> candidateArchiveChunks) {}
    public record ImportPair(ImportResult baseline, ImportResult candidate) {}
    public record Pipeline(long repositoryGithubId, String repositoryFullName, String workflowPath, String harnessSha256, Map<String,String> files) {}
    public enum Arm { BASELINE, CANDIDATE }
    public enum Status { COMPLETE, BUILD_FAILED, BENCHMARK_FAILED, TIMEOUT, NOT_RUN }
    public record ExecutionStatus(@NotNull Status status, Integer exitCode, Instant startedAt, Instant finishedAt,
                                  @PositiveOrZero @Max(900000) long elapsedMs, @Size(max=500) String error) {}
    public record Observation(@NotBlank String identifier, @Pattern(regexp="ns/op") @NotNull String unit,
                              @NotNull @Size(min=5,max=5) List<@NotNull Double> values) {}
    public record Block(@NotNull Arm arm, @Min(0) @Max(2) int forkIndex, @NotNull Status status, Integer exitCode,
                        Instant startedAt, Instant finishedAt, @PositiveOrZero @Max(900000) long elapsedMs, @Size(max=500) String error,
                        @NotNull @Size(max=2) List<@NotNull @Valid Observation> observations) {}
    public record Execution(@Min(1) @Max(1) int schemaVersion, @NotNull @Pattern(regexp="alternating-forks-v1") String strategy,
                            @NotNull @Pattern(regexp="[0-9a-f]{64}") String harnessSha256,
                            @NotNull @Size(min=6,max=6) List<@NotNull Arm> order,
                            @NotNull @Size(min=2,max=2) Map<Arm,@NotNull @Valid ExecutionStatus> builds,
                            @NotNull @Size(min=6,max=6) List<@NotNull @Valid Block> blocks) {}
    public record Archive(Artifact artifact, byte[] runBytes, byte[] archiveBytes, Execution execution) {}
    public record VerifiedArchive(long githubArtifactId, Archive archive, com.fasterxml.jackson.databind.JsonNode evidence) {}
    public record VerifiedPair(VerifiedArchive baseline, VerifiedArchive candidate) {}
}
