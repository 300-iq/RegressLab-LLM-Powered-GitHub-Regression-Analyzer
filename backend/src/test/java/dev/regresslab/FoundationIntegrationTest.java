package dev.regresslab;

import com.fasterxml.jackson.databind.JsonNode;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FoundationIntegrationTest {
    // Actual PostgreSQL 17 binaries, not an in-memory database or SQL emulator.
    private static final EmbeddedPostgres POSTGRES = startPostgres();

    private static EmbeddedPostgres startPostgres() {
        try {
            return EmbeddedPostgres.builder().setPort(0).start();
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> POSTGRES.getJdbcUrl("postgres", "postgres"));
        registry.add("spring.datasource.username", () -> "postgres");
        registry.add("spring.datasource.password", () -> "");
    }

    static dev.regresslab.ai.AiInvestigationService backgroundAi;
    @Autowired void captureAi(dev.regresslab.ai.AiInvestigationService service) { backgroundAi=service; }
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void resetDatabase() {
        jdbc.execute("TRUNCATE repositories CASCADE");
    }

    @AfterAll
    static void closeDatabase() throws IOException {
        if(backgroundAi!=null)backgroundAi.close();
        POSTGRES.close();
    }

    @Test
    void healthIsLiveAndReadinessChecksPostgres() {
        var response = http.getForEntity("/api/v1/health", JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("status").asText()).isEqualTo("UP");
        assertThat(response.getBody().get("service").asText()).isEqualTo("regresslab-api");
        var readiness = http.getForEntity("/actuator/health/readiness", JsonNode.class);
        assertThat(readiness.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(readiness.getBody().toString()).isEqualTo("{\"status\":\"UP\"}");
    }
    @Test
    void actualHttpWritesRemainDisabledInTheDefaultProfile() {
        var headers = new org.springframework.http.HttpHeaders(); headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        for (String path : new String[]{"/api/v1/dev/artifacts", "/api/v1/dev/github-imports", "/api/v1/comparisons",
                "/api/v1/comparisons/00000000-0000-0000-0000-000000000001/analysis",
                "/api/v1/comparisons/00000000-0000-0000-0000-000000000001/ai-investigations"}) {
            var response = http.postForEntity(path, new org.springframework.http.HttpEntity<>("{}", headers), JsonNode.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(response.getBody().path("code").asText()).isEqualTo("NOT_FOUND");
        }
    }

    @Test
    void flywayMigratesAnEmptyDatabaseWithoutSeededOutcomes() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE success", Long.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM comparison_analyses", Long.class)).isZero();
        assertThat(http.getForObject("/api/v1/overview", JsonNode.class).toString())
                .isEqualTo("{\"repositories\":0,\"pullRequests\":0,\"comparisons\":0,\"observations\":0}");
        assertThat(http.getForObject("/api/v1/repositories", JsonNode.class).isEmpty()).isTrue();
    }

    @Test
    void apiReadsPersistedRepositoryIdentity() {
        UUID repositoryId = repository();
        var response = http.getForObject("/api/v1/repositories", JsonNode.class);
        assertThat(response.get(0).get("id").asText()).isEqualTo(repositoryId.toString());
        assertThat(response.get(0).get("githubUrl").asText()).isEqualTo("https://github.com/fixture/demo");
        assertThat(http.getForObject("/api/v1/overview", JsonNode.class).get("repositories").asInt()).isEqualTo(1);
    }

    @Test
    void singletonConstraintEnforcesOneRepository() {
        repository();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO repositories(github_id, full_name) VALUES (2, 'fixture/other')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void repositoryNameCannotBecomeAnArbitraryUrl() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO repositories(github_id, full_name) VALUES (1, 'https://evil.test')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void runAttemptIsUniqueAndInvalidCommitsAreRejected() {
        UUID repositoryId = repository();
        UUID prId = pullRequest(repositoryId);
        comparison(repositoryId, prId, "COMPLETE");
        assertThatThrownBy(() -> comparison(repositoryId, prId, "COMPLETE"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE comparisons SET candidate_sha = 'main'"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE comparisons SET candidate_sha = baseline_sha"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void observationsAreFinitePositiveAndUnique() {
        UUID repo = repository();
        UUID comparison = comparison(repo, pullRequest(repo), "COMPLETE");
        UUID benchmark = benchmark(comparison);
        jdbc.update("INSERT INTO observations VALUES (?, 'BASELINE', 0, 0, 10.0)", benchmark);
        for (String invalid : new String[]{"'NaN'", "'Infinity'", "'-Infinity'", "0", "-1", "1e16"}) {
            assertThatThrownBy(() -> jdbc.update("INSERT INTO observations VALUES (?, 'CANDIDATE', 0, 0, " + invalid + ")", benchmark))
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThatThrownBy(() -> jdbc.update("INSERT INTO observations VALUES (?, 'BASELINE', 0, 0, 11.0)", benchmark))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO observations VALUES (?, 'BASELINE', 10, 0, 11.0)", benchmark))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void incompleteMeasurementsDoNotCountAsCompletedResults() {
        UUID repo = repository();
        UUID comparison = comparison(repo, pullRequest(repo), "PENDING");
        UUID benchmark = benchmark(comparison);
        jdbc.update("INSERT INTO observations VALUES (?, 'BASELINE', 0, 0, 10.0)", benchmark);
        var pending = http.getForObject("/api/v1/overview", JsonNode.class);
        assertThat(pending.get("comparisons").asInt()).isZero();
        assertThat(pending.get("observations").asInt()).isZero();
        jdbc.update("UPDATE comparisons SET status = 'COMPLETE' WHERE id = ?", comparison);
        var complete = http.getForObject("/api/v1/overview", JsonNode.class);
        assertThat(complete.get("comparisons").asInt()).isEqualTo(1);
        assertThat(complete.get("observations").asInt()).isEqualTo(1);
    }

    @Test
    void orphanObservationsCannotBeInserted() {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO observations VALUES (?, 'BASELINE', 0, 0, 10.0)", UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private UUID repository() {
        return jdbc.queryForObject("INSERT INTO repositories(github_id, full_name) VALUES (1, 'fixture/demo') RETURNING id", UUID.class);
    }

    private UUID pullRequest(UUID repo) {
        return jdbc.queryForObject("INSERT INTO pull_requests(repository_id, number, title, state) VALUES (?, 1, 'Synthetic test fixture', 'OPEN') RETURNING id", UUID.class, repo);
    }

    private UUID comparison(UUID repo, UUID pr, String status) {
        return jdbc.queryForObject("""
                INSERT INTO comparisons(repository_id, pull_request_id, workflow_run_id,
                    workflow_run_attempt, baseline_sha, candidate_sha, artifact_sha256,
                    schema_version, status, data_origin, environment, measured_at)
                VALUES (?, ?, 1, 1, ?, ?, ?, 1, ?, 'DEMO', '{}'::jsonb, now()) RETURNING id
                """, UUID.class, repo, pr, "a".repeat(40), "b".repeat(40), "c".repeat(64), status);
    }

    private UUID benchmark(UUID comparison) {
        return jdbc.queryForObject("""
                INSERT INTO comparison_benchmarks(comparison_id, name, parameters, configuration, configuration_sha256)
                VALUES (?, 'fixture.operation', '{}'::jsonb, '{}'::jsonb, ?) RETURNING id
                """, UUID.class, comparison, "d".repeat(64));
    }
}
