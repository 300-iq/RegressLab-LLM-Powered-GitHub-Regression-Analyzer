package dev.regresslab.ai;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import dev.regresslab.ai.AiDtos.*;
import dev.regresslab.analysis.AnalysisDtos.*;
import dev.regresslab.analysis.BootstrapAnalysis;
import dev.regresslab.api.ApiProblem;
import dev.regresslab.domain.ApiDtos.SampleDto;
import dev.regresslab.domain.ArtifactDtos.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.Executors;

/** Offline, developer-authored experiments only. No production eligibility/provenance is bypassed in the API. */
public final class InvestigatorEvaluation {
    private static final ObjectMapper JSON = AiJson.reader(new ObjectMapper().registerModule(new JavaTimeModule())
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS));
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Pass -PevaluationDir=<executed local run directory>");
        Path root = Path.of(args[0]).toAbsolutePath();
        var metadata = JSON.readTree(Files.readString(root.resolve("measurements.json")));
        var upstream = new AiSettings(true, System.getenv().getOrDefault("REGRESSLAB_OLLAMA_URL", "http://127.0.0.1:11434"),
                System.getenv().getOrDefault("REGRESSLAB_OLLAMA_MODEL", "qwen3:4b"), 180);
        var reproducibility = new LinkedHashMap<String,Object>();
        reproducibility.put("reportVersion", OllamaClient.VERSION);
        reproducibility.put("model", upstream.model());
        reproducibility.put("timeoutSeconds", upstream.timeoutSeconds());
        reproducibility.put("systemSha256", hash(OllamaClient.SYSTEM));
        for (var type : List.of(OllamaClient.class, ReportValidator.class, BootstrapAnalysis.class, InvestigatorEvaluation.class)) {
            try (var bytes = type.getResourceAsStream(type.getSimpleName()+".class")) {
                reproducibility.put(type.getSimpleName()+"ClassSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.readAllBytes())));
            }
        }
        try (var bytes = new org.springframework.core.io.ClassPathResource("ai-report.schema.json").getInputStream()) {
            reproducibility.put("schemaSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.readAllBytes())));
        }
        reproducibility.put("javaRuntime", System.getProperty("java.runtime.version"));
        write(root.resolve("model-configuration.json"), reproducibility);
        Path sentinel = Path.of("/tmp/regresslab-eval-injection-sentinel");
        if (Files.exists(sentinel)) throw new IllegalStateException("Injection sentinel already exists; refuse ambiguous evaluation without deleting it.");
        var results = new ArrayList<Object>();
        for (var scenario : metadata.path("scenarios")) {
            String id = scenario.path("id").asText();
            if (!id.matches("[a-z-]{1,40}")) throw new IllegalArgumentException("Invalid evaluation scenario ID");
            Path dir = root.resolve(id); Files.createDirectories(dir);
            // Resolve only validated local paths. Scenario content never becomes a command, URL or template.
            String filePath = scenario.path("filePath").asText();
            if (!PrDiffService.validPath(filePath)) throw new IllegalArgumentException("Invalid changed path");
            String patch = scenario.path("patch").asText();
            if (patch.getBytes(StandardCharsets.UTF_8).length > 6000) throw new IllegalArgumentException("Patch too large");
            var b = samples(scenario.path("baseline")); var c = samples(scenario.path("candidate"));
            String envHash = hash(metadata.path("environment").toString());
            String configHash = hash(metadata.path("configuration").toString());
            var conditions = new BootstrapAnalysis.Conditions("local-evaluation." + id, "average_time", "ns/op",
                    Direction.LOWER, envHash, configHash, 3, 5, 3);
            var policy = Policy.defaults(5, 1);
            var result = BootstrapAnalysis.compare(b, c, conditions, conditions, policy);
            var conditionData = JSON.createObjectNode();
            conditionData.put("executionOrigin", "LOCAL_EXECUTED_EVALUATION_NOT_GITHUB");
            conditionData.put("scope", "Developer-authored local controlled sources; not a public GitHub PR. IDs 0 mean not applicable.");
            conditionData.set("baselineEnvironment", metadata.path("environment"));
            conditionData.set("candidateEnvironment", metadata.path("environment"));
            conditionData.set("baselineConfiguration", metadata.path("configuration"));
            conditionData.set("candidateConfiguration", metadata.path("configuration"));
            conditionData.set("executionOrderAndContention", scenario.path("executionLedger"));
            conditionData.put("conditionsWarning", id.equals("noisy-control")
                    ? "Deliberately varying real CPU contention differs between forks and arms; no code-induced regression established."
                    : "Local scheduling, contention and thermal drift are unmeasured confounders.");
            UUID comparison = UUID.nameUUIDFromBytes((metadata.path("runId").asText()+id).getBytes(StandardCharsets.UTF_8));
            var analysis = new AnalysisDto(UUID.nameUUIDFromBytes((comparison+"analysis").getBytes(StandardCharsets.UTF_8)), comparison,
                    BootstrapAnalysis.VERSION, hash(JSON.writeValueAsString(policy)), hash(scenario.toString()), policy, result,
                    DataOrigin.REAL, "LOCAL_EXECUTED_EVALUATION_NOT_GITHUB", Instant.parse(metadata.path("completedAt").asText()));
            var diff = new Diff("LOCAL_EVALUATION_NOT_GITHUB", 0, 0, metadata.path("baselineCommit").asText(),
                    scenario.path("candidateCommit").asText(metadata.path("candidateCommit").asText()), List.of(new ChangedFile(filePath, filePath, patch, PrDiffService.parsePatch(patch))));
            var input = new Input(diff, new Evidence(analysis, null, null, b, c, envHash, envHash, conditionData));
            write(dir.resolve("input.json"), input);
            var attempt = new LinkedHashMap<String,Object>();
            attempt.put("id", id); attempt.put("reportVersion", OllamaClient.VERSION);
            attempt.put("productionThresholdGuardWouldDecline", result.relativeChangePercent()!=null && Math.abs(result.relativeChangePercent()) < 5);
            attempt.put("policy", "OFFLINE_CLIENT_EVALUATION_ALL_CONTROLS_NO_PRODUCTION_API_WRITE");
            // Record rejected envelopes as well as accepted ones, without adding diagnostics to public API errors.
            try (var recorder = new Recorder(upstream, dir); var ignored = new ClientResource(
                    new OllamaClient(new AiSettings(true, recorder.origin(), upstream.model(), upstream.timeoutSeconds()), JSON))) {
                long start = System.nanoTime();
                try {
                    Completion completion = ignored.client.generate(input);
                    write(dir.resolve("report.json"), completion.report());
                    attempt.put("status", "ACCEPTED_UNVERIFIED_HYPOTHESES"); attempt.put("model", completion.model());
                    attempt.put("reviewWarnings", ReportValidator.reviewWarnings(completion.report()));
                } catch (ApiProblem problem) {
                    attempt.put("status", "REJECTED"); attempt.put("errorCode", problem.code()); attempt.put("detail", problem.getMessage());
                } finally { attempt.put("elapsedSeconds", (System.nanoTime()-start)/1e9); }
            }
            write(dir.resolve("attempt.json"), attempt); results.add(attempt);
            System.out.println(id + ": " + attempt.get("status") + " (" + attempt.get("elapsedSeconds") + "s)");
        }
        write(root.resolve("attempts.json"), results);
        write(root.resolve("safety.json"), Map.of("injectionSentinelAbsentBefore", true,
                "injectionSentinelAbsentAfter", !Files.exists(sentinel), "modelToolsProvided", false,
                "modelOutputCommandsExecuted", false, "productionApiWrites", false));
        if (Files.exists(sentinel)) throw new IllegalStateException("Injection sentinel appeared; investigate external/application execution.");
    }
    static List<SampleDto> samples(JsonNode forks) {
        if (!forks.isArray() || forks.size()!=3) throw new IllegalArgumentException("Expected 3 executed forks");
        var result = new ArrayList<SampleDto>();
        for (int f=0;f<3;f++) {
            if (!forks.get(f).isArray() || forks.get(f).size()!=5) throw new IllegalArgumentException("Expected 5 executed iterations");
            for (int i=0;i<5;i++) {
                var value=forks.get(f).get(i);
                if (!value.isNumber() || !Double.isFinite(value.doubleValue()) || value.doubleValue()<=0)
                    throw new IllegalArgumentException("Invalid actual measurement");
                result.add(new SampleDto(f,i,value.doubleValue()));
            }
        }
        return List.copyOf(result);
    }
    static String hash(String s) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8))); }
    static void write(Path path, Object value) throws Exception {
        Files.writeString(path, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value)+"\n", StandardOpenOption.CREATE_NEW);
    }
    record ClientResource(OllamaClient client) implements AutoCloseable { public void close() { client.close(); } }
    /** Loopback-only recording transport, fixed upstream, no tools/execution. Bounded failed envelopes retained. */
    static final class Recorder implements AutoCloseable {
        final HttpServer server;
        final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        Recorder(AiSettings upstream, Path dir) throws Exception {
            var transport = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(3)).build();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0); server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try {
                    if (!exchange.getRemoteAddress().getAddress().isLoopbackAddress()
                            || List.of("Origin", "Sec-Fetch-Site", "Forwarded", "X-Forwarded-For", "X-Real-IP")
                            .stream().anyMatch(h -> exchange.getRequestHeaders().containsKey(h))) {
                        exchange.sendResponseHeaders(403,-1); return;
                    }
                    String path=exchange.getRequestURI().getPath(); String method=exchange.getRequestMethod();
                    if (method.equals("POST") && !"application/json".equals(exchange.getRequestHeaders().getFirst("Content-Type"))) {
                        exchange.sendResponseHeaders(415,-1); return;
                    }
                    if (!exchange.getRequestURI().toString().equals(path) || !(path.equals("/api/tags") && method.equals("GET")
                            || path.equals("/api/chat") && method.equals("POST"))) { exchange.sendResponseHeaders(404,-1); return; }
                    byte[] body=exchange.getRequestBody().readNBytes(65537);
                    if (body.length>65536) { exchange.sendResponseHeaders(413,-1); return; }
                    var request=HttpRequest.newBuilder(URI.create(upstream.ollamaUrl()).resolve(path)).timeout(Duration.ofSeconds(upstream.timeoutSeconds()))
                            .header("Content-Type","application/json");
                    if (method.equals("POST")) {
                        Files.write(dir.resolve("request.json"),body,StandardOpenOption.CREATE_NEW);
                        request.POST(HttpRequest.BodyPublishers.ofByteArray(body));
                    } else request.GET();
                    var response=transport.send(request.build(),HttpResponse.BodyHandlers.ofInputStream());
                    byte[] bytes;
                    try (var stream=response.body()) { bytes=stream.readNBytes(65537); }
                    if (method.equals("POST")) Files.write(dir.resolve("raw-response.json"),bytes,StandardOpenOption.CREATE_NEW);
                    else Files.write(dir.resolve("model-tags.json"),bytes,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
                    exchange.getResponseHeaders().set("Content-Type","application/json");
                    exchange.sendResponseHeaders(response.statusCode(),bytes.length); exchange.getResponseBody().write(bytes);
                } catch (Exception e) { try { exchange.sendResponseHeaders(503,-1); } catch (Exception ignored) { /* already closed */ } }
                finally { exchange.close(); }
            }); server.start();
        }
        String origin() { return "http://127.0.0.1:"+server.getAddress().getPort(); }
        public void close() { server.stop(0); executor.shutdownNow(); }
    }
}
