package dev.regresslab.domain;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import dev.regresslab.domain.ArtifactDtos.DataOrigin;
import dev.regresslab.domain.ArtifactDtos.Direction;
import dev.regresslab.domain.ArtifactDtos.RunStatus;

public final class ApiDtos {
    private ApiDtos() {}
    public record OverviewDto(long repositories, long pullRequests, long comparisons, long observations) {}
    public record Page<T>(List<T> items, int page, int size, long totalElements) {}
    public record RepositoryDto(UUID id, String fullName, String githubUrl, String owner, String name,
                                DataOrigin dataOrigin, String provenance) {}
    public record PullRequestDto(UUID id, UUID repositoryId, int number, String title, String githubUrl,
                                 String state, String baselineSha, String candidateSha, Instant updatedAt,
                                 DataOrigin dataOrigin, String provenance) {}
    public record RunDto(UUID id, UUID repositoryId, UUID pullRequestId, long workflowRunId, int runAttempt,
                         String githubUrl, String commitSha, String baselineSha, String candidateSha,
                         String branch, Instant measuredAt, JsonNode environment, String environmentSha256,
                         RunStatus status, DataOrigin dataOrigin, String provenance, UUID artifactId,
                         String artifactIdentifier, String artifactSha256, Instant importedAt) {}
    public record CaseDto(UUID id, UUID repositoryId, String identifier, String metricName, String unit, Direction direction) {}
    public record RunBenchmarkDto(CaseDto benchmarkCase, JsonNode parameters, JsonNode configuration,
                                  String configurationSha256, long sampleCount) {}
    public record RunDetail(RunDto run, List<RunBenchmarkDto> benchmarks) {}
    public record SampleDto(int forkIndex, int iterationIndex, double value) {}
    public record SamplePage(UUID runId, UUID benchmarkCaseId, String unit, DataOrigin dataOrigin, Page<SampleDto> samples) {}
    public record PrDetail(PullRequestDto pullRequest, Page<RunDto> runs, Page<ComparisonDto> comparisons) {}
    public record ComparisonRequest(@NotNull UUID baselineRunId, @NotNull UUID candidateRunId, @NotNull UUID benchmarkCaseId) {}
    public record ImportResult(UUID repositoryId, UUID pullRequestId, UUID runId, UUID artifactId,
                               String artifactSha256, DataOrigin dataOrigin, String provenance, boolean replayed) {}
    public record Summary(int baselineForks, int candidateForks, int baselineSamples, int candidateSamples,
                          double baselineMean, double candidateMean, double baselineMedian, double candidateMedian,
                          double baselineStdDev, double candidateStdDev, double baselineCv, double candidateCv,
                          double candidateBaselineRatio, double degradationPercent) {}
    public record ComparisonDto(UUID id, UUID repositoryId, UUID baselineRunId, UUID candidateRunId, UUID benchmarkCaseId,
                                String analysisVersion, String analysisStatus, Summary summary, List<String> reasons,
                                DataOrigin dataOrigin, String provenance, Instant createdAt) {}
    public record HistoryPoint(RunDto run, JsonNode parameters, JsonNode configuration,
                               String configurationSha256, int forks, long sampleCount, double mean) {}
}
