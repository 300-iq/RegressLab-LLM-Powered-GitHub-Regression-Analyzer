package dev.regresslab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("local-ingestion")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class BackendPersistenceIntegrationTest {
    static final String TOKEN = "test-only-local-token-01234567890123456789";
    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static EmbeddedPostgres startPostgres() {
        try { return EmbeddedPostgres.builder().setPort(0).start(); }
        catch (IOException e) { throw new ExceptionInInitializerError(e); }
    }
    @DynamicPropertySource static void settings(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres"); registry.add("spring.datasource.password", () -> "");
        registry.add("regresslab.local-ingestion.token", () -> TOKEN);
        registry.add("regresslab.local-ingestion.repository-github-id", () -> 1);
        registry.add("regresslab.local-ingestion.repository-full-name", () -> "regresslab-demo/java-benchmarks");
        registry.add("regresslab.local-ingestion.requests-per-minute", () -> 1000);
        registry.add("regresslab.ai.enabled", () -> true);
    }
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired dev.regresslab.ai.AiReportRepository aiReports;
    @Autowired dev.regresslab.ai.AiJobRepository aiJobs;
    static dev.regresslab.ai.AiInvestigationService backgroundAi;
    @Autowired void captureAi(dev.regresslab.ai.AiInvestigationService service) { backgroundAi=service; }
    @Autowired dev.regresslab.analysis.ComparisonAnalysisService analysisService;
    @BeforeEach void reset() { jdbc.execute("TRUNCATE repositories CASCADE"); }
    @AfterAll static void stop() throws IOException { if(backgroundAi!=null)backgroundAi.close(); POSTGRES.close(); }

    HttpHeaders headers() { var h = new HttpHeaders(); h.setContentType(MediaType.APPLICATION_JSON); h.setBearerAuth(TOKEN); return h; }
    ResponseEntity<JsonNode> send(String url, String body) { return http.exchange(url, HttpMethod.POST, new HttpEntity<>(body, headers()), JsonNode.class); }
    ResponseEntity<JsonNode> upload(ObjectNode node) { return send("/api/v1/dev/artifacts", node.toString()); }
    JsonNode importRun(String artifactId, boolean candidate) {
        var r = upload(TestArtifacts.artifact(mapper, artifactId, candidate));
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED); return r.getBody();
    }
    JsonNode get(String url) {
        var r = http.getForEntity(url, JsonNode.class); assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK); return r.getBody();
    }
    String caseId(JsonNode run) { return get("/api/v1/runs/" + run.path("runId").asText()).path("benchmarks").get(0).path("benchmarkCase").path("id").asText(); }
    String comparisonBody(JsonNode b, JsonNode c, String caseId) {
        return "{\"baselineRunId\":\"" + b.path("runId").asText() + "\",\"candidateRunId\":\"" + c.path("runId").asText() + "\",\"benchmarkCaseId\":\"" + caseId + "\"}";
    }

    @Test void importsPersistedRecordsAndReplayIsIdempotent() {
        var artifact = TestArtifacts.artifact(mapper, "baseline", false);
        var first = upload(artifact); assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getHeaders().getLocation().toString()).startsWith("/api/v1/runs/");
        var result = first.getBody();
        var replay = upload(artifact); assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody().path("runId")).isEqualTo(result.path("runId")); assertThat(replay.getBody().path("replayed").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM benchmark_samples", Long.class)).isEqualTo(15);
        assertThat(jdbc.queryForObject("SELECT raw_json FROM ingested_artifacts", byte[].class)).isEqualTo(TestArtifacts.bytes(artifact));
        var repo = get("/api/v1/repositories").get(0);
        assertThat(repo.path("owner").asText()).isEqualTo("regresslab-demo"); assertThat(repo.path("dataOrigin").asText()).isEqualTo("DEMO");
        var detail = get("/api/v1/pull-requests/" + result.path("pullRequestId").asText());
        assertThat(detail.path("pullRequest").path("baselineSha").asText()).isEqualTo(TestArtifacts.BASE);
        assertThat(detail.path("runs").path("items").get(0).path("artifactSha256").asText()).isEqualTo(result.path("artifactSha256").asText());
        var samples = get("/api/v1/runs/" + result.path("runId").asText() + "/cases/" + caseId(result) + "/samples?page=1&size=5");
        assertThat(samples.path("samples").path("totalElements").asInt()).isEqualTo(15);
        assertThat(samples.path("samples").path("items").get(0).path("forkIndex").asInt()).isEqualTo(1);
        assertThat(get("/api/v1/overview").path("observations").asInt()).isEqualTo(15);
        String repoId = result.path("repositoryId").asText();
        assertThat(get("/api/v1/repositories/" + repoId + "/runs").path("totalElements").asInt()).isZero();
        assertThat(get("/api/v1/repositories/" + repoId + "/runs?origin=DEMO").path("totalElements").asInt()).isEqualTo(1);
        assertThat(get("/api/v1/repositories/" + repoId + "/pull-requests?origin=DEMO").path("items").size()).isEqualTo(1);
    }
    @Test void aiReportsRoundTripInPostgresAndReadsDoNotGenerateReports() throws Exception {
        var comparison = comparisonForAnalysis(); UUID comparisonId=UUID.fromString(comparison.path("id").asText());
        var analysis=analysisService.compute(comparisonId,new dev.regresslab.analysis.AnalysisDtos.Request(null));
        var evidence=new dev.regresslab.ai.AiDtos.Evidence(analysis,"https://github.com/fixture/demo/actions/runs/1","https://github.com/fixture/demo/actions/runs/1",List.of(),List.of(),"a".repeat(64),"a".repeat(64),mapper.createObjectNode());
        var input=new dev.regresslab.ai.AiDtos.Input(new dev.regresslab.ai.AiDtos.Diff("fixture/demo",1,1,TestArtifacts.BASE,TestArtifacts.HEAD,List.of()),evidence);
        var report=new dev.regresslab.ai.AiDtos.ModelReport(List.of(),List.of("Synthetic isolated persistence test; no model was called"));
        var completion=new dev.regresslab.ai.AiDtos.Completion(new dev.regresslab.ai.AiDtos.ModelIdentity("test:model","a".repeat(64)),report,"{\"testFixture\":true}");
        var stored=aiReports.insert(comparisonId,"b".repeat(64),input,completion);
        assertThat(stored.status()).isEqualTo("NO_HYPOTHESES");
        assertThat(aiReports.get(stored.id())).isEqualTo(stored);
        assertThat(get("/api/v1/ai-investigations/"+stored.id())).isEqualTo(mapper.readTree(mapper.writeValueAsString(stored)));
        assertThat(get("/api/v1/comparisons/"+comparisonId+"/ai-investigations").path("totalElements").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT raw_response FROM ai_investigation_reports",String.class)).isEqualTo(completion.rawResponse());
        assertThatThrownBy(() -> jdbc.update("UPDATE ai_investigation_reports SET report='{}'::jsonb")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE ai_investigation_reports SET status='VERIFIED_CAUSE'")).isInstanceOf(DataIntegrityViolationException.class);
        for (String query : List.of("?page=-1","?size=101"))
            assertThat(http.getForEntity("/api/v1/comparisons/"+comparisonId+"/ai-investigations"+query,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    @Test void aiJobsSerializeEnqueueReplayReuseAndFailureWithoutChangingReports() throws Exception {
        var comparison=comparisonForAnalysis();UUID cid=UUID.fromString(comparison.path("id").asText());
        var analysis=analysisService.compute(cid,new dev.regresslab.analysis.AnalysisDtos.Request(null));
        var first=aiJobs.enqueue(cid,analysis.id(),"a".repeat(64));
        assertThat(first.created()).isTrue();assertThat(aiJobs.enqueue(cid,analysis.id(),"a".repeat(64)).job().id()).isEqualTo(first.job().id());
        assertThatThrownBy(()->aiJobs.enqueue(cid,analysis.id(),"b".repeat(64))).isInstanceOf(dev.regresslab.api.ApiProblem.class);
        var evidence=new dev.regresslab.ai.AiDtos.Evidence(analysis,"local persistence test","local persistence test",List.of(),List.of(),"a".repeat(64),"a".repeat(64),mapper.createObjectNode());
        var input=new dev.regresslab.ai.AiDtos.Input(new dev.regresslab.ai.AiDtos.Diff("fixture/demo",1,1,TestArtifacts.BASE,TestArtifacts.HEAD,List.of()),evidence);
        var model=new dev.regresslab.ai.AiDtos.ModelIdentity("test:model","a".repeat(64));
        var completion=new dev.regresslab.ai.AiDtos.Completion(model,new dev.regresslab.ai.AiDtos.ModelReport(List.of(),List.of("Explicit synthetic persistence test; no model call")),"test raw response");
        aiJobs.running(first.job().id());
        var success=aiJobs.complete(first.job().id(),()->aiReports.insert(cid,"a".repeat(64),input,completion),false);
        var stored=aiReports.get(success.reportId());
        assertThat(aiReports.reusable("a".repeat(64),model)).contains(stored);
        assertThat(aiReports.reusable("b".repeat(64),model)).isEmpty();
        assertThat(aiReports.reusable("a".repeat(64),new dev.regresslab.ai.AiDtos.ModelIdentity(model.name(),"b".repeat(64)))).isEmpty();
        var second=aiJobs.enqueue(cid,analysis.id(),"a".repeat(64));aiJobs.running(second.job().id());
        assertThat(aiJobs.complete(second.job().id(),()->stored,true).reused()).isTrue();
        var failed=aiJobs.enqueue(cid,analysis.id(),"a".repeat(64));aiJobs.fail(failed.job().id(),"OLLAMA_UNAVAILABLE","Offline");
        assertThat(aiJobs.get(failed.job().id()).status()).isEqualTo(dev.regresslab.ai.AiDtos.JobStatus.FAILED);
        assertThat(aiReports.get(stored.id())).isEqualTo(stored);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_investigation_reports",Integer.class)).isEqualTo(1);
        assertThat(get("/api/v1/ai-investigation-jobs/"+success.id()).path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(get("/api/v1/comparisons/"+cid+"/ai-investigation-jobs").path("totalElements").asInt()).isEqualTo(3);
    }
    @Test void jobCompletionAndReportInsertRollbackTogether() {
        var c=comparisonForAnalysis();UUID cid=UUID.fromString(c.path("id").asText());
        var a=analysisService.compute(cid,new dev.regresslab.analysis.AnalysisDtos.Request(null));
        var queued=aiJobs.enqueue(cid,a.id(),"a".repeat(64));
        var e=new dev.regresslab.ai.AiDtos.Evidence(a,"test","test",List.of(),List.of(),"a".repeat(64),"a".repeat(64),mapper.createObjectNode());
        var input=new dev.regresslab.ai.AiDtos.Input(new dev.regresslab.ai.AiDtos.Diff("fixture/demo",1,1,TestArtifacts.BASE,TestArtifacts.HEAD,List.of()),e);
        var completion=new dev.regresslab.ai.AiDtos.Completion(new dev.regresslab.ai.AiDtos.ModelIdentity("test:model","a".repeat(64)),new dev.regresslab.ai.AiDtos.ModelReport(List.of(),List.of("Synthetic test")),"{}");
        assertThatThrownBy(()->aiJobs.complete(queued.job().id(),()->aiReports.insert(cid,"a".repeat(64),input,completion),false)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_investigation_reports",Integer.class)).isZero();
        assertThat(aiJobs.get(queued.job().id()).status()).isEqualTo(dev.regresslab.ai.AiDtos.JobStatus.QUEUED);
        jdbc.update("UPDATE ai_investigation_jobs SET created_at=now()-interval '2 minutes'");
        aiJobs.withWorkerLock(aiJobs::recoverInterrupted);
        assertThat(aiJobs.get(queued.job().id()).errorCode()).isEqualTo("AI_JOB_INTERRUPTED");
    }
    @Test void globalWorkerLockAndConcurrentReplayUseActualPostgres() throws Exception {
        var c=comparisonForAnalysis();UUID cid=UUID.fromString(c.path("id").asText());
        var a=analysisService.compute(cid,new dev.regresslab.analysis.AnalysisDtos.Request(null));
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var first=executor.submit(()->aiJobs.enqueue(cid,a.id(),"a".repeat(64)));
            var second=executor.submit(()->aiJobs.enqueue(cid,a.id(),"a".repeat(64)));
            var x=first.get(5,java.util.concurrent.TimeUnit.SECONDS);var y=second.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(x.job().id()).isEqualTo(y.job().id());assertThat(x.created()).isNotEqualTo(y.created());
            var held=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
            var worker=executor.submit(()->aiJobs.withWorkerLock(()->{held.countDown();try{release.await(5,java.util.concurrent.TimeUnit.SECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}}));
            try {assertThat(held.await(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();assertThat(aiJobs.withWorkerLock(()->{throw new AssertionError("Second worker entered");})).isFalse();}
            finally{release.countDown();}
            assertThat(worker.get(5,java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            assertThat(aiJobs.withWorkerLock(()->{})).isTrue();
        }
    }
    @Test void aiGenerationDeniesAnonymousInvalidAndSyntheticEvidenceBeforeCallingOllama() {
        var comparison=comparisonForAnalysis(); UUID id=UUID.fromString(comparison.path("id").asText());
        var analysis=analysisService.compute(id,new dev.regresslab.analysis.AnalysisDtos.Request(null));
        String path="/api/v1/comparisons/"+id+"/ai-investigations";
        var publicHeaders=new HttpHeaders(); publicHeaders.setContentType(MediaType.APPLICATION_JSON);
        String body="{\"analysisId\":\""+analysis.id()+"\"}";
        assertThat(http.postForEntity(path,new HttpEntity<>(body,publicHeaders),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(send(path,"{}").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(send(path,body).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_investigation_reports",Integer.class)).isZero();
    }
    @Test void stableArtifactAndRunConflictsDoNotOverwriteRecords() {
        var artifact = TestArtifacts.artifact(mapper, "baseline", false); upload(artifact);
        var changed = artifact.deepCopy(); ((ObjectNode) changed.path("pullRequest")).put("title", "Changed fixture");
        assertThat(upload(changed).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        changed.put("artifactId", "other-artifact"); assertThat(upload(changed).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT title FROM pull_requests", String.class)).contains("DEMO");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ingested_artifacts", Long.class)).isEqualTo(1);
    }
    @Test void rejectsBadInputAuthenticationAndOversizedRequests() {
        String input = TestArtifacts.artifact(mapper, "invalid", false).toString();
        var noToken = new HttpHeaders(); noToken.setContentType(MediaType.APPLICATION_JSON);
        assertThat(http.postForEntity("/api/v1/dev/artifacts", new HttpEntity<>(input, noToken), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        noToken.setBearerAuth("wrong");
        assertThat(http.postForEntity("/api/v1/dev/artifacts", new HttpEntity<>(input, noToken), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        for (String json : List.of("{", input + " {}", input.replace("\"schemaVersion\":2", "\"schemaVersion\":2,\"schemaVersion\":2")))
            assertThat(send("/api/v1/dev/artifacts", json).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var wrong = TestArtifacts.artifact(mapper, "invalid", false);
        ((ObjectNode)wrong.path("benchmarks").get(0).path("samples").get(0)).put("value", -1);
        assertThat(upload(wrong).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(send("/api/v1/dev/artifacts", " ".repeat(1048577)).getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        var proxy = headers(); proxy.set("X-Forwarded-For", "203.0.113.2");
        assertThat(http.postForEntity("/api/v1/dev/artifacts", new HttpEntity<>(input, proxy), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM repositories", Long.class)).isZero();
    }
    @Test void lateCaseConflictRollsBackPrRunArtifactAndSamples() {
        importRun("baseline", false);
        var incompatible = TestArtifacts.artifact(mapper, "candidate", true);
        var benchmark = (ObjectNode)incompatible.path("benchmarks").get(0);
        benchmark.put("metricName", "throughput").put("unit", "ops/s").put("direction", "HIGHER");
        ((ObjectNode)incompatible.path("pullRequest")).put("title", "Must roll back");
        assertThat(upload(incompatible).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM benchmark_runs", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ingested_artifacts", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM benchmark_samples", Long.class)).isEqualTo(15);
        assertThat(jdbc.queryForObject("SELECT title FROM pull_requests", String.class)).doesNotContain("Must roll back");
    }
    @Test void refusesNonAllowlistedRepositoriesAndMixedOrigins() {
        var disallowed = TestArtifacts.artifact(mapper, "wrong-repo", false);
        ((ObjectNode)disallowed.path("repository")).put("githubId", 2);
        assertThat(upload(disallowed).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        importRun("baseline", false);
        var real = TestArtifacts.artifact(mapper, "candidate", true); real.put("dataOrigin", "REAL");
        assertThat(upload(real).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
    @Test void comparisonsArePersistedReproducibleAndNeverClaimSignificance() {
        var b = importRun("baseline", false); var c = importRun("candidate", true); String caseId = caseId(b);
        String body = comparisonBody(b, c, caseId);
        var first = send("/api/v1/comparisons", body); assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        var comparison = first.getBody();
        assertThat(comparison.path("analysisStatus").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(comparison.path("summary").path("baselineForks").asInt()).isEqualTo(3);
        assertThat(comparison.path("summary").path("baselineSamples").asInt()).isEqualTo(15);
        assertThat(comparison.path("summary").path("degradationPercent").asDouble()).isCloseTo(20 / 101.2 * 100, within(1e-8));
        assertThat(send("/api/v1/comparisons", body).getBody().path("id")).isEqualTo(comparison.path("id"));
        assertThat(get("/api/v1/comparisons/" + comparison.path("id").asText())).isEqualTo(comparison);
        assertThat(get("/api/v1/pull-requests/" + b.path("pullRequestId").asText() + "/comparisons").path("totalElements").asInt()).isEqualTo(1);
        assertThat(get("/api/v1/cases/" + caseId + "/history?origin=DEMO").path("totalElements").asInt()).isEqualTo(2);
        assertThat(get("/api/v1/cases/" + caseId + "/history").path("totalElements").asInt()).isZero();
        assertThat(get("/api/v1/cases/" + caseId + "/history?origin=DEMO&environmentSha256=" + "0".repeat(64)).path("totalElements").asInt()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM run_comparisons", Long.class)).isEqualTo(1);
    }
    @Test void incompatibleEnvironmentAndConfigurationProduceNoComputedEffect() {
        var b = importRun("baseline", false); String caseId = caseId(b);
        var candidate = TestArtifacts.artifact(mapper, "different-cpu", true);
        ((ObjectNode)candidate.path("run").path("environment")).put("cpu", "Other DEMO CPU");
        var c = upload(candidate).getBody();
        var result = send("/api/v1/comparisons", comparisonBody(b, c, caseId)).getBody();
        assertThat(result.path("analysisStatus").asText()).isEqualTo("INCOMPATIBLE");
        assertThat(result.path("summary").isNull()).isTrue(); assertThat(result.path("reasons").toString()).contains("ENVIRONMENT_MISMATCH");
        var other = TestArtifacts.artifact(mapper, "different-config", true);
        ((ObjectNode)other.path("run")).put("runAttempt", 2);
        ((ObjectNode)other.path("benchmarks").get(0).path("parameters")).put("entries", "20000");
        var c2 = upload(other).getBody();
        var config = send("/api/v1/comparisons", comparisonBody(b, c2, caseId)).getBody();
        assertThat(config.path("summary").isNull()).isTrue(); assertThat(config.path("reasons").toString()).contains("CONFIGURATION_MISMATCH");
    }
    @Test void rejectsDifferentCasesReversedPairsAndFailedRuns() {
        var b = importRun("baseline", false); String caseId = caseId(b);
        var different = TestArtifacts.artifact(mapper, "different-case", true);
        ((ObjectNode)different.path("benchmarks").get(0)).put("identifier", "dev.regresslab.OtherCase.v1");
        var c = upload(different).getBody();
        assertThat(send("/api/v1/comparisons", comparisonBody(b, c, caseId)).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send("/api/v1/comparisons", comparisonBody(b, b, caseId)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(send("/api/v1/comparisons", comparisonBody(c, b, caseId)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var failed = TestArtifacts.artifact(mapper, "failed", true);
        ((ObjectNode)failed.path("run")).put("status", "FAILED").put("runAttempt", 2); failed.set("benchmarks", mapper.createArrayNode());
        var failedRun = upload(failed).getBody();
        assertThat(send("/api/v1/comparisons", comparisonBody(b, failedRun, caseId)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
    @Test void concurrentIdenticalImportsCreateOneRun() throws Exception {
        String body = TestArtifacts.artifact(mapper, "concurrent", false).toString();
        try (var executor = Executors.newFixedThreadPool(4)) {
            var tasks = java.util.stream.IntStream.range(0, 4).<java.util.concurrent.Callable<ResponseEntity<JsonNode>>>mapToObj(i -> () -> send("/api/v1/dev/artifacts", body)).toList();
            var results = executor.invokeAll(tasks).stream().map(f -> { try { return f.get(); } catch (Exception e) { throw new RuntimeException(e); } }).toList();
            assertThat(results.stream().map(r -> r.getStatusCode().value()).sorted().toList()).containsExactly(200, 200, 200, 201);
            assertThat(results.stream().map(r -> r.getBody().path("runId").asText()).distinct().count()).isEqualTo(1);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM benchmark_runs", Long.class)).isEqualTo(1);
    }
    @Test void validatesQueryParametersAndMissingResourcesWithConsistentProblems() {
        for (String url : List.of("/api/v1/runs/not-a-uuid", "/api/v1/repositories/" + UUID.randomUUID() + "/runs?page=-1", "/api/v1/repositories/" + UUID.randomUUID() + "/runs?size=101")) {
            var response = http.getForEntity(url, JsonNode.class); assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        }
        assertThat(http.getForEntity("/api/v1/runs/" + UUID.randomUUID(), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(send("/api/v1/comparisons", "{}").getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
    @Test void databaseAlsoRejectsInvalidAndOrphanSamples() {
        var b = importRun("baseline", false); UUID run = UUID.fromString(b.path("runId").asText()); UUID caseId = UUID.fromString(caseId(b));
        for (String invalid : List.of("'NaN'", "'Infinity'", "'-Infinity'", "0", "-1"))
            assertThatThrownBy(() -> jdbc.update("UPDATE benchmark_samples SET value = " + invalid + " WHERE run_id = ?", run)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO benchmark_samples VALUES (?, ?, 0, 0, 10)", UUID.randomUUID(), caseId)).isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void unsupportedMethodsAdvertiseTheAllowedMethod() {
        var response = http.getForEntity("/api/v1/dev/artifacts", JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getAllow()).contains(HttpMethod.POST);
    }
    @Test void emptyHistoryOriginUsesTheDocumentedDefault() {
        var b = importRun("baseline", false);
        var response = http.getForEntity("/api/v1/cases/" + caseId(b) + "/history?origin=", JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    @Test void invalidWireValuesAreRejectedBeforeAnyDatabaseWrite() {
        var fractional = TestArtifacts.artifact(mapper, "fractional", false);
        ((ObjectNode)fractional.path("run")).put("workflowRunId", 1001.5);
        assertThat(upload(fractional).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        String invalidUnicode = TestArtifacts.artifact(mapper, "bad-unicode", false).toString()
                .replace("\"title\":\"DEMO: synthetic map lookup change\"", "\"title\":\"\\uD800\"");
        assertThat(send("/api/v1/dev/artifacts", invalidUnicode).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM repositories", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ingested_artifacts", Long.class)).isZero();
    }
    @Test void newAnalysisVersionDoesNotOverwriteAnExistingAuditRecord() {
        var baseline = importRun("baseline", false); var candidate = importRun("candidate", true);
        String body = comparisonBody(baseline, candidate, caseId(baseline));
        var old = send("/api/v1/comparisons", body).getBody();
        // Simulate a previously persisted v1 analysis in this isolated test DB.
        jdbc.update("UPDATE run_comparisons SET analysis_version = 'fork-descriptive-v1' WHERE id = ?", UUID.fromString(old.path("id").asText()));
        var snapshot = get("/api/v1/comparisons/" + old.path("id").asText());
        var current = send("/api/v1/comparisons", body).getBody();
        assertThat(current.path("analysisVersion").asText()).isEqualTo("fork-descriptive-v2");
        assertThat(current.path("id")).isNotEqualTo(old.path("id"));
        assertThat(get("/api/v1/comparisons/" + old.path("id").asText())).isEqualTo(snapshot);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM run_comparisons", Long.class)).isEqualTo(2);
    }

    private JsonNode comparisonForAnalysis() {
        var b = importRun("analysis-baseline", false); var c = importRun("analysis-candidate", true);
        return send("/api/v1/comparisons", comparisonBody(b, c, caseId(b))).getBody();
    }
    @Test void listsStoredAnalysesWithoutWriteCredentialsOrImplicitComputation() {
        var comparison = comparisonForAnalysis();
        String path = "/api/v1/comparisons/" + comparison.path("id").asText();
        assertThat(get(path + "/analyses").path("totalElements").asInt()).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM comparison_analyses", Integer.class)).isZero();
        var first = send(path + "/analysis", "{}").getBody();
        var second = send(path + "/analysis", "{\"practicalThresholdPercent\":10}").getBody();
        var page = get(path + "/analyses?size=1");
        assertThat(page.path("totalElements").asInt()).isEqualTo(2);
        assertThat(page.path("items").get(0)).isEqualTo(second);
        assertThat(get(path + "/analyses?page=1&size=1").path("items").get(0)).isEqualTo(first);
        for (String query : List.of("?page=-1", "?size=101"))
            assertThat(http.getForEntity(path + "/analyses" + query, JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/api/v1/comparisons/" + UUID.randomUUID() + "/analyses", JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
    @Test void databaseRejectsMissingNullOrTextPracticalThreshold() {
        var comparison = comparisonForAnalysis();
        var analysis = send("/api/v1/comparisons/" + comparison.path("id").asText() + "/analysis", "{}").getBody();
        UUID id = UUID.fromString(analysis.path("id").asText());
        for (String malformed : List.of("{}", "{\"practicalThresholdPercent\":null}", "{\"practicalThresholdPercent\":\"5\"}"))
            assertThatThrownBy(() -> jdbc.update("UPDATE comparison_analyses SET policy = ?::jsonb WHERE id = ?", malformed, id))
                    .isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void databaseRejectsNullOrMissingAnalysisClassification() {
        var comparison = comparisonForAnalysis();
        var analysis = send("/api/v1/comparisons/" + comparison.path("id").asText() + "/analysis", "{}").getBody();
        UUID id = UUID.fromString(analysis.path("id").asText());
        for (String malformed : List.of("{\"classification\":null}", "{}"))
            assertThatThrownBy(() -> jdbc.update("UPDATE comparison_analyses SET result = ?::jsonb WHERE id = ?", malformed, id))
                    .isInstanceOf(DataIntegrityViolationException.class);
    }
    @Test void subnormalRawSamplesRemainStoredWhileAnalysisRefusesInference() {
        JsonNode[] arms = new JsonNode[2];
        for (int arm = 0; arm < 2; arm++) {
            var artifact = TestArtifacts.artifact(mapper, "subnormal-" + arm, arm == 1);
            var samples = (ArrayNode) artifact.path("benchmarks").get(0).path("samples");
            for (int i = 0; i < samples.size(); i++) ((ObjectNode) samples.get(i)).put("value", Double.MIN_VALUE * (10 + i));
            var response = upload(artifact); assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED); arms[arm] = response.getBody();
        }
        var comparison = send("/api/v1/comparisons", comparisonBody(arms[0], arms[1], caseId(arms[0])));
        assertThat(comparison.getStatusCode()).isEqualTo(HttpStatus.OK);
        var response = send("/api/v1/comparisons/" + comparison.getBody().path("id").asText() + "/analysis", "{}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var result = response.getBody().path("result");
        assertThat(result.path("classification").asText()).isEqualTo("INVALID_COMPARISON");
        assertThat(result.path("reasons").toString()).contains("UNSUPPORTED_SUBNORMAL_SCALE");
        assertThat(result.path("relativeChangePercent").isNull()).isTrue(); assertThat(result.path("interval").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM benchmark_samples", Integer.class)).isEqualTo(30);
        assertThat(jdbc.queryForObject("SELECT min(value) > 0 AND max(value) < ? FROM benchmark_samples", Boolean.class, Double.MIN_NORMAL)).isTrue();
    }
    @Test void tinyNormalValuesRoundTripThroughJsonbAnalysis() {
        JsonNode[] arms = new JsonNode[2];
        for (int arm = 0; arm < 2; arm++) {
            var artifact = TestArtifacts.artifact(mapper, "tiny-normal-" + arm, arm == 1);
            var samples = (ArrayNode) artifact.path("benchmarks").get(0).path("samples");
            for (var sample : samples) ((ObjectNode) sample).put("value", sample.path("value").asDouble() * 1e-200);
            var response = upload(artifact); assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED); arms[arm] = response.getBody();
        }
        var comparison = send("/api/v1/comparisons", comparisonBody(arms[0], arms[1], caseId(arms[0])));
        assertThat(comparison.getStatusCode()).isEqualTo(HttpStatus.OK);
        var response = send("/api/v1/comparisons/" + comparison.getBody().path("id").asText() + "/analysis", "{}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var analysis = response.getBody();
        assertThat(analysis.path("result").path("baseline").path("forkMeans").path("standardDeviation").asDouble()).isGreaterThan(0);
        assertThat(analysis.path("result").path("classification").asText()).isEqualTo("INCONCLUSIVE");
        assertThat(get(response.getHeaders().getLocation().toString())).isEqualTo(analysis);
    }
    @Test void bootstrapAnalysisPersistsReplaysAndRetainsTheOldComparisonContract() {
        var comparison = comparisonForAnalysis();
        String path = "/api/v1/comparisons/" + comparison.path("id").asText() + "/analysis";
        var response = send(path, "{}"); assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var result = response.getBody();
        assertThat(result.path("analysisVersion").asText()).isEqualTo("fork-bootstrap-v2");
        assertThat(result.path("dataOrigin").asText()).isEqualTo("DEMO");
        assertThat(result.path("policy").path("practicalThresholdPercent").asDouble()).isEqualTo(5);
        assertThat(result.path("result").path("classification").asText()).isEqualTo("INCONCLUSIVE");
        assertThat(result.path("result").path("baseline").path("rawMeasurements").path("count").asInt()).isEqualTo(15);
        assertThat(result.path("result").path("baseline").path("forkMeans").path("count").asInt()).isEqualTo(3);
        assertThat(result.path("result").path("interval").path("exploratory").asBoolean()).isTrue();
        assertThat(result.path("inputSha256").asText()).matches("[0-9a-f]{64}");
        assertThat(get(response.getHeaders().getLocation().toString())).isEqualTo(result);
        assertThat(send(path, "{\"practicalThresholdPercent\":5.0}").getBody()).isEqualTo(result);
        assertThat(get("/api/v1/comparisons/" + comparison.path("id").asText())).isEqualTo(comparison);
        assertThat(send(path, "{\"practicalThresholdPercent\":10}").getBody().path("id")).isNotEqualTo(result.path("id"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM comparison_analyses", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM benchmark_samples", Long.class)).isEqualTo(30);
    }
    @Test void bootstrapPolicyValidationAndAnonymousDenialPrecedeWrites() {
        var comparison = comparisonForAnalysis(); String path = "/api/v1/comparisons/" + comparison.path("id").asText() + "/analysis";
        var noToken = new HttpHeaders(); noToken.setContentType(MediaType.APPLICATION_JSON);
        assertThat(http.postForEntity(path, new HttpEntity<>("{}", noToken), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        for (String body : List.of("{\"practicalThresholdPercent\":0}", "{\"practicalThresholdPercent\":51}",
                "{\"practicalThresholdPercent\":\"5\"}", "{\"seed\":42}", "{\"practicalThresholdPercent\":5,\"practicalThresholdPercent\":10}",
                "{\"practicalThresholdPercent\":5." + "0".repeat(40) + "}")) {
            var response = send(path, body);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        }
        assertThat(send("/api/v1/comparisons/" + UUID.randomUUID() + "/analysis", "{}").getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.getForEntity("/api/v1/comparison-analyses/" + UUID.randomUUID(), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM comparison_analyses", Long.class)).isZero();
    }
    @Test void analysisOfIncompatibleRecordedEnvironmentsNeverReturnsAnInterval() {
        var b = importRun("baseline", false);
        var other = TestArtifacts.artifact(mapper, "candidate", true);
        ((ObjectNode)other.path("run").path("environment")).put("cpu", "DEMO other CPU");
        var c = upload(other).getBody();
        var comparison = send("/api/v1/comparisons", comparisonBody(b, c, caseId(b))).getBody();
        assertThat(comparison.path("analysisStatus").asText()).isEqualTo("INCOMPATIBLE");
        var analysis = send("/api/v1/comparisons/" + comparison.path("id").asText() + "/analysis", "{}").getBody().path("result");
        assertThat(analysis.path("classification").asText()).isEqualTo("INVALID_COMPARISON");
        assertThat(analysis.path("interval").isNull()).isTrue(); assertThat(analysis.path("relativeChangePercent").isNull()).isTrue();
        assertThat(analysis.path("environmentWarnings").size()).isGreaterThan(0);
    }
    @Test void familyAdjustmentIncludesEveryCommonCaseAndLocalTenForksRemainExploratory() {
        JsonNode[] imported = new JsonNode[2];
        for (int arm = 0; arm < 2; arm++) {
            var node = TestArtifacts.artifact(mapper, "family-" + arm, arm == 1);
            var benchmark = (ObjectNode) node.path("benchmarks").get(0);
            ((ObjectNode)benchmark.path("configuration")).put("forkCount", 10);
            var samples = mapper.createArrayNode();
            for (int f = 0; f < 10; f++) for (int i = 0; i < 5; i++)
                samples.add(mapper.valueToTree(java.util.Map.of("forkIndex", f, "iterationIndex", i, "value", (arm == 0 ? 100 : 120) + f * .1)));
            benchmark.set("samples", samples);
            var second = benchmark.deepCopy().put("identifier", "dev.regresslab.second");
            ((ArrayNode)node.path("benchmarks")).add(second);
            var response = upload(node); assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED); imported[arm] = response.getBody();
        }
        var comparison = send("/api/v1/comparisons", comparisonBody(imported[0], imported[1], caseId(imported[0]))).getBody();
        var response = send("/api/v1/comparisons/" + comparison.path("id").asText() + "/analysis", "{}");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        var result = response.getBody();
        assertThat(result.path("policy").path("familySize").asInt()).isEqualTo(2);
        assertThat(result.path("result").path("interval").path("nominalConfidenceLevel").asDouble()).isEqualTo(.975);
        assertThat(result.path("result").path("classification").asText()).isEqualTo("INCONCLUSIVE");
        assertThat(result.path("result").path("reasons").toString()).contains("EXECUTION_PROTOCOL_UNVERIFIED");
    }
}
