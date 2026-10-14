package dev.regresslab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.regresslab.api.ApiProblem;
import dev.regresslab.ingestion.ArtifactValidator;
import dev.regresslab.ingestion.Digests;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;

class ArtifactValidatorTest {
    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final ArtifactValidator validator = new ArtifactValidator(mapper, Validation.buildDefaultValidatorFactory().getValidator());
    @Test void acceptsAValidRunAndRetainsIndividualSamples() {
        var result = validator.parse(TestArtifacts.bytes(TestArtifacts.artifact(mapper, "valid", false)));
        assertThat(result.benchmarks().getFirst().samples()).hasSize(15);
    }
    @Test void rejectsMalformedDuplicateKeysTrailingValuesAndStringCoercion() {
        String valid = TestArtifacts.artifact(mapper, "valid", false).toString();
        for (String input : List.of("{", "null", valid + " {}", valid.replace("\"schemaVersion\":2", "\"schemaVersion\":2,\"schemaVersion\":2"),
                valid.replace("\"workflowRunId\":1001", "\"workflowRunId\":\"1001\""))) {
            assertThatThrownBy(() -> validator.parse(input.getBytes(StandardCharsets.UTF_8))).isInstanceOf(ApiProblem.class);
        }
    }
    @Test void rejectsOversizedAndDeeplyNestedArtifacts() {
        assertThatThrownBy(() -> validator.parse(new byte[ArtifactValidator.MAX_BYTES + 1])).isInstanceOf(ApiProblem.class);
        String deep = "[".repeat(17) + "0" + "]".repeat(17);
        assertThatThrownBy(() -> validator.parse(deep.getBytes(StandardCharsets.UTF_8))).isInstanceOf(ApiProblem.class);
    }
    @Test void validatesNumbersIndicesMetadataAndCounts() {
        List<Consumer<ObjectNode>> mutations = List.of(
                x -> x.put("script", "execute me"),
                x -> x.put("schemaVersion", 3),
                x -> ((ObjectNode)x.path("run")).put("commitSha", "c".repeat(40)),
                x -> ((ObjectNode)x.path("run")).put("measuredAt", "2100-01-01T00:00:00Z"),
                x -> ((ObjectNode)x.path("run").path("environment")).put("javaVersion", "25"),
                x -> ((ObjectNode)x.path("benchmarks").get(0)).put("unit", "ops/s"),
                x -> ((ObjectNode)x.path("benchmarks").get(0).path("samples").get(1)).put("iterationIndex", 0),
                x -> ((ObjectNode)x.path("benchmarks").get(0).path("samples").get(0)).put("value", -1),
                x -> ((ObjectNode)x.path("benchmarks").get(0).path("samples").get(0)).put("value", 1e16),
                x -> ((ObjectNode)x.path("benchmarks").get(0).path("configuration")).put("forkCount", 4),
                x -> ((ObjectNode)x.path("run")).put("status", "FAILED"));
        for (var mutate : mutations) {
            var artifact = TestArtifacts.artifact(mapper, "invalid", false); mutate.accept(artifact);
            assertThatThrownBy(() -> validator.parse(TestArtifacts.bytes(artifact))).isInstanceOf(ApiProblem.class);
        }
        String valid = TestArtifacts.artifact(mapper, "invalid-number", false).toString();
        for (String number : List.of("NaN", "Infinity", "1e999", "1e-999")) {
            assertThatThrownBy(() -> validator.parse(valid.replaceFirst("\"value\":100\\.0", "\"value\":" + number).getBytes(StandardCharsets.UTF_8)))
                    .isInstanceOf(ApiProblem.class);
        }
    }
    @Test void canonicalDigestsIgnoreObjectKeyOrderingButPreserveArrayOrder() {
        assertThat(Digests.canonical(mapper, Map.of("b", 2, "a", 1))).isEqualTo(Digests.canonical(mapper, Map.of("a", 1, "b", 2)));
        assertThat(Digests.canonical(mapper, List.of(1, 2))).isNotEqualTo(Digests.canonical(mapper, List.of(2, 1)));
    }
    @Test void rejectsMissingZeroValuedSampleIndices() {
        for (String field : List.of("forkIndex", "iterationIndex")) {
            var artifact = TestArtifacts.artifact(mapper, "missing-index", false);
            ((ObjectNode)artifact.path("benchmarks").get(0).path("samples").get(0)).remove(field);
            assertThatThrownBy(() -> validator.parse(TestArtifacts.bytes(artifact))).isInstanceOf(ApiProblem.class);
        }
    }
    @ParameterizedTest @ValueSource(strings = {"forkIndex", "iterationIndex", "workflowRunId"})
    void neverTruncatesFractionalIdentifiersOrIndices(String field) {
        var artifact = TestArtifacts.artifact(mapper, "fractional-run", false);
        if (field.equals("workflowRunId")) ((ObjectNode)artifact.path("run")).put(field, 1001.5);
        else ((ObjectNode)artifact.path("benchmarks").get(0).path("samples").get(0)).put(field, 0.5);
        assertThatThrownBy(() -> validator.parse(TestArtifacts.bytes(artifact))).isInstanceOf(ApiProblem.class);
    }
    @ParameterizedTest @ValueSource(strings = {"\\uD800", "\\uDC00"})
    void rejectsUnpairedUnicodeSurrogates(String escape) {
        String body = TestArtifacts.artifact(mapper, "bad-unicode", false).toString()
                .replace("\"title\":\"DEMO: synthetic map lookup change\"", "\"title\":\"" + escape + "\"");
        assertThatThrownBy(() -> validator.parse(body.getBytes(StandardCharsets.UTF_8))).isInstanceOf(ApiProblem.class);
    }
    @Test void preservesValidSupplementaryUnicodeCharacters() {
        var artifact = TestArtifacts.artifact(mapper, "valid-unicode", false);
        String title = "DEMO: investigate \uD83D\uDE80 throughput";
        ((ObjectNode)artifact.path("pullRequest")).put("title", title);
        assertThat(validator.parse(TestArtifacts.bytes(artifact)).pullRequest().title()).isEqualTo(title);
    }
}
