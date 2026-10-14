package dev.regresslab.ai;

import dev.regresslab.analysis.AnalysisDtos.AnalysisDto;
import dev.regresslab.domain.ApiDtos.SampleDto;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AiDtos {
    private AiDtos() {}
    public enum Side { BASELINE, CANDIDATE }
    public record Reference(String filePath, Side side, int line) {}
    public record Hypothesis(String potentialCause, List<Reference> references, UUID comparisonId,
                             String technicalExplanation, String verificationExperiment, String uncertainty) {}
    public record ModelReport(List<Hypothesis> hypotheses, List<String> missingEvidence) {}
    public record ChangedLine(Side side, int line, String code) {}
    public record ChangedFile(String filePath, String previousFilePath, String patch, List<ChangedLine> changedLines) {}
    public record Diff(String repository, long githubRepositoryId, int pullRequestNumber,
                       String baselineSha, String candidateSha, List<ChangedFile> files) {}
    public record Evidence(AnalysisDto analysis, String baselineWorkflowUrl, String candidateWorkflowUrl,
                           List<SampleDto> baselineSamples, List<SampleDto> candidateSamples,
                           String baselineEnvironmentSha256, String candidateEnvironmentSha256,
                           com.fasterxml.jackson.databind.JsonNode conditions) {}
    public record Input(Diff diff, Evidence evidence) {}
    public record ModelIdentity(String name, String digest) {}
    public record Completion(ModelIdentity model, ModelReport report, String rawResponse) {}
    public record ReportDto(UUID id, UUID comparisonId, String reportVersion, String inputSha256,
                            String model, String modelDigest, String status, Input input,
                            ModelReport report, List<String> limitations, Instant createdAt) {}
    public enum JobStatus { QUEUED, RUNNING, SUCCEEDED, FAILED }
    public record JobDto(UUID id, UUID comparisonId, UUID analysisId, JobStatus status, UUID reportId,
                         boolean reused, String errorCode, String detail, Instant createdAt, Instant updatedAt) {}
    public record Request(@jakarta.validation.constraints.NotNull UUID analysisId) {}
}
