package dev.regresslab.ingestion;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import dev.regresslab.api.ApiProblem;
import dev.regresslab.domain.ArtifactDtos.*;
import jakarta.validation.Validator;
import java.io.IOException;
import java.util.HashSet;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class ArtifactValidator {
    public static final int MAX_BYTES = 1048576;
    private final Validator validator;
    private final ObjectReader reader;
    private final ObjectMapper mapper;

    public ArtifactValidator(ObjectMapper mapper, Validator validator) {
        this.validator = validator;
        this.mapper = mapper;
        ObjectMapper strict = mapper.copy();
        strict.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(16).maxStringLength(2000).maxNameLength(200).maxNumberLength(32).build());
        strict.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        strict.setConfig(strict.getDeserializationConfig().without(MapperFeature.ALLOW_COERCION_OF_SCALARS));
        reader = strict.readerFor(Artifact.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS).without(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
    }

    public Artifact parse(byte[] bytes) {
        if (bytes.length == 0) throw new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_JSON", "A JSON artifact is required.");
        if (bytes.length > MAX_BYTES) throw new ApiProblem(HttpStatus.PAYLOAD_TOO_LARGE, "ARTIFACT_TOO_LARGE", "Artifact exceeds 1 MiB.");
        Artifact artifact;
        try {
            requireUtf8(bytes);
            artifact = reader.readValue(bytes);
        } catch (IOException exception) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_JSON", "Malformed or unsupported JSON structure.");
        }
        if (artifact == null) throw ApiProblem.invalid("Artifact must be an object.");
        var errors = validator.validate(artifact);
        if (!errors.isEmpty()) {
            String field = errors.stream().map(e -> e.getPropertyPath().toString()).sorted().findFirst().orElse("artifact");
            throw ApiProblem.invalid("Invalid field: " + field.replaceAll("\\[[^]]*]", "[*]"));
        }
        rejectInvalidText(mapper.valueToTree(artifact));
        if (artifact.repository().name().equals(".") || artifact.repository().name().equals(".."))
            throw ApiProblem.invalid("Invalid repository name.");
        var pr = artifact.pullRequest();
        if (pr.baselineSha().equals(pr.candidateSha())) throw ApiProblem.invalid("Baseline and candidate commits must differ.");
        if (!artifact.run().commitSha().equals(pr.baselineSha()) && !artifact.run().commitSha().equals(pr.candidateSha()))
            throw ApiProblem.invalid("Run commit does not match the PR commit pair.");
        if (artifact.run().measuredAt().isBefore(java.time.Instant.parse("1970-01-01T00:00:00Z"))
                || !artifact.run().measuredAt().isBefore(java.time.Instant.parse("2100-01-01T00:00:00Z")))
            throw ApiProblem.invalid("Measurement timestamp must be between 1970 and 2100.");
        if (artifact.run().status() == RunStatus.FAILED && !artifact.benchmarks().isEmpty())
            throw ApiProblem.invalid("Failed runs must not contain measurements.");
        if (artifact.run().status() == RunStatus.COMPLETE && artifact.benchmarks().isEmpty())
            throw ApiProblem.invalid("Completed runs require measurements.");
        var cases = new HashSet<String>();
        int total = 0;
        for (CaseInput benchmark : artifact.benchmarks()) {
            if (!cases.add(benchmark.identifier())) throw ApiProblem.invalid("Duplicate benchmark case identifier.");
            boolean latency = benchmark.metricName().equals("average_time") && benchmark.unit().equals("ns/op") && benchmark.direction() == Direction.LOWER;
            boolean throughput = benchmark.metricName().equals("throughput") && benchmark.unit().equals("ops/s") && benchmark.direction() == Direction.HIGHER;
            if (!latency && !throughput) throw ApiProblem.invalid("Metric, unit and direction are incompatible.");
            var config = benchmark.configuration();
            if (benchmark.samples().size() != config.forkCount() * config.measurementIterations())
                throw ApiProblem.invalid("Sample count must match fork and iteration counts.");
            var indices = new HashSet<Integer>();
            for (SampleInput sample : benchmark.samples()) {
                if (!Double.isFinite(sample.value()) || sample.value() <= 0 || sample.value() > 1e15)
                    throw ApiProblem.invalid("Measurements must be finite, positive and at most 1e15.");
                if (sample.forkIndex() >= config.forkCount() || sample.iterationIndex() >= config.measurementIterations()
                        || !indices.add(sample.forkIndex() * 30 + sample.iterationIndex()))
                    throw ApiProblem.invalid("Sample indices must be unique, contiguous and match configuration.");
            }
            total += benchmark.samples().size();
        }
        if (total > 6000) throw ApiProblem.invalid("Too many observations in one run.");
        return artifact;
    }

    public static void rejectInvalidText(com.fasterxml.jackson.databind.JsonNode node) {
        if (node.isTextual()) {
            String text = node.textValue();
            for (int i = 0; i < text.length(); i++) {
                char character = text.charAt(i);
                if (character == '\0') throw ApiProblem.invalid("Text must not contain NUL characters.");
                if (Character.isHighSurrogate(character)) {
                    if (++i >= text.length() || !Character.isLowSurrogate(text.charAt(i)))
                        throw ApiProblem.invalid("Text must contain valid Unicode scalar values.");
                } else if (Character.isLowSurrogate(character)) {
                    throw ApiProblem.invalid("Text must contain valid Unicode scalar values.");
                }
            }
        }
        if (node.isContainerNode()) node.forEach(ArtifactValidator::rejectInvalidText);
    }

    public static void requireUtf8(byte[] bytes) {
        try {
            java.nio.charset.StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes));
            for (byte value : bytes) if (value == 0) throw new IOException("Not UTF-8 JSON");
        } catch (IOException exception) {
            throw new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_JSON", "Send UTF-8 JSON without binary NUL bytes.");
        }
    }
}
