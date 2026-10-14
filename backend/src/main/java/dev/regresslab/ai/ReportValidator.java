package dev.regresslab.ai;

import dev.regresslab.ai.AiDtos.*;
import dev.regresslab.api.ApiProblem;
import java.util.HashSet;
import org.springframework.http.HttpStatus;

public final class ReportValidator {
    private ReportValidator() {}
    public static void validate(ModelReport report, Input input) {
        if (report == null || report.hypotheses() == null || report.hypotheses().size() > 5
                || report.missingEvidence() == null || report.missingEvidence().isEmpty() || report.missingEvidence().size() > 10) invalid();
        var allowed = new HashSet<Reference>();
        for (var file : input.diff().files()) for (var line : file.changedLines())
            allowed.add(new Reference(file.filePath(), line.side(), line.line()));
        for (var h : report.hypotheses()) {
            if (h == null || !input.evidence().analysis().comparisonId().equals(h.comparisonId())
                    || h.references() == null || h.references().isEmpty() || h.references().size() > 10
                    || new HashSet<>(h.references()).size() != h.references().size()) invalid();
            text(h.potentialCause()); text(h.technicalExplanation()); text(h.verificationExperiment()); text(h.uncertainty());
            for (var ref : h.references()) if (!allowed.contains(ref)) invalid();
        }
        report.missingEvidence().forEach(ReportValidator::text);
    }
    private static void text(String value) {
        if (value == null || value.isBlank() || value.length() > 2000
                || value.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\t')) invalid();
        try { dev.regresslab.ingestion.ArtifactValidator.rejectInvalidText(new com.fasterxml.jackson.databind.node.TextNode(value)); }
        catch (ApiProblem e) { invalid(); }
    }
    public static void invalid() {
        throw new ApiProblem(HttpStatus.BAD_GATEWAY, "INVALID_AI_RESPONSE",
                "Ollama returned an invalid or incomplete report, or references outside the supplied changed lines and benchmark evidence. No completed report was stored.");
    }
    public static java.util.List<String> reviewWarnings(ModelReport report) {
        var warnings = new java.util.ArrayList<String>();
        for (int i=0;i<report.hypotheses().size();i++) {
            var h=report.hypotheses().get(i);
            String experiment=h.verificationExperiment().toLowerCase(java.util.Locale.ROOT);
            if (experiment.matches("(?s).*(single.{0,80}(iteration|run|sample|invocation|call)|skip.{0,30}warmup|avoid.{0,30}warmup).*"))
                warnings.add("Hypothesis "+(i+1)+" mentions a single measurement or bypassing warmup. Such an experiment is inadequate regression evidence; retain warmup and repeated independent forks.");
            String prose=(h.technicalExplanation()+" "+h.uncertainty()).toLowerCase(java.util.Locale.ROOT);
            if (prose.matches("(?s).*(confidence|significan|variance|fork diversity).*"))
                warnings.add("Hypothesis "+(i+1)+" includes model-written statistical commentary. Use the supplied backend analysis for measurement claims; this prose has not been statistically verified.");
        }
        return java.util.List.copyOf(warnings);
    }
}
