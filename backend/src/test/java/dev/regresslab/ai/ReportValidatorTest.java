package dev.regresslab.ai;

import dev.regresslab.ai.AiDtos.*;
import dev.regresslab.api.ApiProblem;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ReportValidatorTest {
    @Test void acceptsOnlySuppliedChangedLinesAndRealComparisonIdentifiers() { ReportValidator.validate(AiTestData.report(),AiTestData.input()); }
    @Test void rejectsInventedFileSideUnchangedLineAndBenchmark() {
        var h = AiTestData.report().hypotheses().getFirst();
        for (var ref : List.of(new Reference("evil.java",Side.CANDIDATE,2),new Reference("src/Example.java",Side.CANDIDATE,3),new Reference("src/Example.java",null,2)))
            assertThatThrownBy(() -> ReportValidator.validate(new ModelReport(List.of(new Hypothesis(h.potentialCause(),List.of(ref),h.comparisonId(),h.technicalExplanation(),h.verificationExperiment(),h.uncertainty())),List.of("Unknown")),AiTestData.input()))
                    .isInstanceOf(ApiProblem.class).hasMessageContaining("references");
        assertThatThrownBy(() -> ReportValidator.validate(new ModelReport(List.of(new Hypothesis(h.potentialCause(),h.references(),UUID.randomUUID(),h.technicalExplanation(),h.verificationExperiment(),h.uncertainty())),List.of("Unknown")),AiTestData.input())).isInstanceOf(ApiProblem.class);
    }
    @Test void requiresUncertaintyAndBoundsTextAndHypothesisCount() {
        var h = AiTestData.report().hypotheses().getFirst();
        for (var report : List.of(new ModelReport(Collections.nCopies(6,h),List.of("Unknown")),new ModelReport(List.of(h),List.of()),
                new ModelReport(List.of(new Hypothesis("x".repeat(2001),h.references(),h.comparisonId(),h.technicalExplanation(),h.verificationExperiment(),h.uncertainty())),List.of("Unknown")),
                new ModelReport(List.of(new Hypothesis(h.potentialCause(),h.references(),h.comparisonId(),h.technicalExplanation(),h.verificationExperiment(),"")),List.of("Unknown"))))
            assertThatThrownBy(() -> ReportValidator.validate(report,AiTestData.input())).isInstanceOf(ApiProblem.class);
    }
    @Test void emptyResultsAreValidWithExplicitMissingEvidence() { ReportValidator.validate(new ModelReport(List.of(),List.of("No useful hypothesis supported")),AiTestData.input()); }
    @Test void flagsObservedUnsoundExperimentsWithoutRewritingOrHidingTheModelOutput() {
        var h=AiTestData.report().hypotheses().getFirst();
        var report=new ModelReport(List.of(new Hypothesis(h.potentialCause(),h.references(),h.comparisonId(),h.technicalExplanation(),
                "Use a single iteration and avoid JVM warmup", "High variance and statistical confidence")),List.of("Unknown"));
        assertThat(ReportValidator.reviewWarnings(report)).hasSize(2);
        assertThat(report.hypotheses().getFirst().verificationExperiment()).contains("avoid JVM warmup");
        assertThat(ReportValidator.reviewWarnings(AiTestData.report())).isEmpty();
    }
    @Test void neverAcceptsRemoteOllamaOriginsOrInvalidConfiguration() {
        for (String url : List.of("https://ollama.example:11434","http://127.0.0.1:11434/evil","http://user:pass@localhost:11434","http://localhost:11434?redirect=evil","http://localhost"))
            assertThatThrownBy(() -> new AiSettings(true,url,"qwen3:4b",120)).isInstanceOf(IllegalArgumentException.class);
        assertThat(new AiSettings(true,"http://127.0.0.1:11434","qwen3:4b",120).enabled()).isTrue();
    }
}
