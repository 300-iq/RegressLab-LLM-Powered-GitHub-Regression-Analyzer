package dev.regresslab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class TestArtifacts {
    private TestArtifacts() {}
    public static final String BASE = "a".repeat(40);
    public static final String HEAD = "b".repeat(40);
    public static ObjectNode artifact(ObjectMapper mapper, String identifier, boolean candidate) {
        List<Map<String, Object>> samples = new ArrayList<>();
        for (int fork = 0; fork < 3; fork++) for (int iteration = 0; iteration < 5; iteration++)
            samples.add(Map.of("forkIndex", fork, "iterationIndex", iteration, "value", (candidate ? 120 : 100) + fork + iteration * .1));
        return mapper.valueToTree(Map.of(
                "schemaVersion", 2, "artifactId", identifier, "dataOrigin", "DEMO",
                "repository", Map.of("githubId", 1, "owner", "regresslab-demo", "name", "java-benchmarks"),
                "pullRequest", Map.of("number", 42, "title", "DEMO: synthetic map lookup change", "state", "OPEN", "baselineSha", BASE, "candidateSha", HEAD),
                "run", Map.of("workflowRunId", 1001, "runAttempt", 1, "commitSha", candidate ? HEAD : BASE,
                        "branch", candidate ? "demo/map-lookup" : "main", "measuredAt", "2026-10-13T12:00:00Z", "status", "COMPLETE",
                        "environment", Map.of("runner", "DEMO synthetic runner", "os", "Linux", "architecture", "x86_64", "cpu", "DEMO synthetic CPU", "javaVersion", "21.0.9", "jmhVersion", "1.37")),
                "benchmarks", List.of(Map.of("identifier", "dev.regresslab.MapLookup.v1", "metricName", "average_time", "unit", "ns/op", "direction", "LOWER",
                        "parameters", Map.of("entries", "10000"), "configuration", Map.of("warmupIterations", 3, "measurementIterations", 5,
                                "forkCount", 3, "warmupTimeMs", 1000, "measurementTimeMs", 1000, "jvmArgs", List.of("-Xms256m", "-Xmx256m")), "samples", samples))));
    }
    public static byte[] bytes(ObjectNode node) { return node.toString().getBytes(StandardCharsets.UTF_8); }
}
