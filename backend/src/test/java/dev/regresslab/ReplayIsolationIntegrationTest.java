package dev.regresslab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.sql.Connection;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.*;

@ActiveProfiles("local-ingestion")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.hikari.transaction-isolation=TRANSACTION_REPEATABLE_READ")
class ReplayIsolationIntegrationTest {
    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static final String TOKEN = "test-only-isolation-token-01234567890123456789";
    private static EmbeddedPostgres startPostgres() {
        try { return EmbeddedPostgres.builder().setPort(0).start(); }
        catch (IOException exception) { throw new ExceptionInInitializerError(exception); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres"); registry.add("spring.datasource.password", () -> "");
        registry.add("regresslab.local-ingestion.token", () -> TOKEN);
        registry.add("regresslab.local-ingestion.repository-github-id", () -> 1);
        registry.add("regresslab.local-ingestion.repository-full-name", () -> "regresslab-demo/java-benchmarks");
        registry.add("regresslab.local-ingestion.requests-per-minute", () -> 1000);
    }
    static dev.regresslab.ai.AiInvestigationService backgroundAi;
    @Autowired void captureAi(dev.regresslab.ai.AiInvestigationService service) { backgroundAi=service; }
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @Autowired DataSource dataSource;
    @BeforeEach void clear() { jdbc.execute("TRUNCATE repositories CASCADE"); }
    @AfterAll static void stop() throws IOException { if(backgroundAi!=null)backgroundAi.close(); POSTGRES.close(); }

    private ResponseEntity<JsonNode> post(String path, String body) {
        var headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); headers.setBearerAuth(TOKEN);
        return http.postForEntity(path, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private List<ResponseEntity<JsonNode>> simultaneouslyAfterWaitingOnLock(Callable<ResponseEntity<JsonNode>> call) throws Exception {
        try (Connection holder = POSTGRES.getPostgresDatabase().getConnection(); var executor = Executors.newFixedThreadPool(2)) {
            holder.setAutoCommit(false);
            holder.createStatement().execute("SELECT pg_advisory_xact_lock(726547831)");
            var first = executor.submit(call); var second = executor.submit(call);
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND objid = 726547831 AND NOT granted", Integer.class) < 2
                        && System.nanoTime() < deadline) Thread.sleep(10);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND objid = 726547831 AND NOT granted", Integer.class)).isEqualTo(2);
            } finally { holder.commit(); }
            return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
        }
    }
    @Test void identicalImportsRemainIdempotentWithRepeatableReadConnectionDefaults() throws Exception {
        try (Connection connection = dataSource.getConnection()) { assertThat(connection.getTransactionIsolation()).isEqualTo(Connection.TRANSACTION_REPEATABLE_READ); }
        String body = TestArtifacts.artifact(mapper, "same-artifact", false).toString();
        var results = simultaneouslyAfterWaitingOnLock(() -> post("/api/v1/dev/artifacts", body));
        assertThat(results.stream().map(r -> r.getStatusCode().value()).sorted().toList()).containsExactly(200, 201);
        assertThat(results.get(0).getBody().path("runId")).isEqualTo(results.get(1).getBody().path("runId"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM benchmark_runs", Long.class)).isEqualTo(1);
    }
    @Test void identicalComparisonComputationsRemainIdempotentWithRepeatableReadDefaults() throws Exception {
        var baseline = post("/api/v1/dev/artifacts", TestArtifacts.artifact(mapper, "baseline", false).toString()).getBody();
        var candidate = post("/api/v1/dev/artifacts", TestArtifacts.artifact(mapper, "candidate", true).toString()).getBody();
        var detail = http.getForObject("/api/v1/runs/" + baseline.path("runId").asText(), JsonNode.class);
        String caseId = detail.path("benchmarks").get(0).path("benchmarkCase").path("id").asText();
        String body = "{\"baselineRunId\":\"" + baseline.path("runId").asText() + "\",\"candidateRunId\":\"" + candidate.path("runId").asText() + "\",\"benchmarkCaseId\":\"" + caseId + "\"}";
        var results = simultaneouslyAfterWaitingOnLock(() -> post("/api/v1/comparisons", body));
        assertThat(results.stream().map(r -> r.getStatusCode().value()).toList()).containsOnly(200);
        assertThat(results.get(0).getBody().path("id")).isEqualTo(results.get(1).getBody().path("id"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM run_comparisons", Long.class)).isEqualTo(1);
    }
    @Test void concurrentBootstrapCalculationsReplayAfterWaitingWithRepeatableReadDefaults() throws Exception {
        var baseline = post("/api/v1/dev/artifacts", TestArtifacts.artifact(mapper, "bootstrap-baseline", false).toString()).getBody();
        var candidate = post("/api/v1/dev/artifacts", TestArtifacts.artifact(mapper, "bootstrap-candidate", true).toString()).getBody();
        var detail = http.getForObject("/api/v1/runs/" + baseline.path("runId").asText(), JsonNode.class);
        var request = mapper.createObjectNode().put("baselineRunId", baseline.path("runId").asText())
                .put("candidateRunId", candidate.path("runId").asText())
                .put("benchmarkCaseId", detail.path("benchmarks").get(0).path("benchmarkCase").path("id").asText());
        var comparison = post("/api/v1/comparisons", request.toString()).getBody();
        String path = "/api/v1/comparisons/" + comparison.path("id").asText() + "/analysis";
        var results = simultaneouslyAfterWaitingOnLock(() -> post(path, "{}"));
        assertThat(results.stream().map(r -> r.getStatusCode().value()).toList()).containsOnly(200);
        assertThat(results.get(0).getBody()).isEqualTo(results.get(1).getBody());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM comparison_analyses", Long.class)).isEqualTo(1);
    }
}
