package dev.regresslab;

import com.fasterxml.jackson.databind.JsonNode;
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import java.io.IOException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DatabaseOutageIntegrationTest {
    private static final EmbeddedPostgres POSTGRES = startPostgres();
    private static boolean closed;

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
        registry.add("spring.datasource.hikari.connection-timeout", () -> 1000);
    }

    static dev.regresslab.ai.AiInvestigationService backgroundAi;
    @Autowired void captureAi(dev.regresslab.ai.AiInvestigationService service) { backgroundAi=service; }
    @Autowired TestRestTemplate http;

    @AfterAll
    static void closeDatabase() throws IOException {
        if(backgroundAi!=null)backgroundAi.close();
        if (!closed) POSTGRES.close();
    }

    @Test
    void actualDatabaseOutageReturnsUnavailableWithoutExposingInternals() throws IOException {
        assertThat(http.getForEntity("/actuator/health/readiness", JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        POSTGRES.close();
        closed = true;
        assertThat(http.getForEntity("/api/v1/health", JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/actuator/health/readiness", JsonNode.class).getStatusCode())
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        for (String endpoint : new String[]{"/api/v1/overview", "/api/v1/repositories"}) {
            var response = http.getForEntity(endpoint, JsonNode.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(response.getBody().get("detail").asText())
                    .isEqualTo("The database is unavailable. Please retry later.");
            assertThat(response.getBody().toString()).doesNotContain("jdbc", "password", "SQLException", "stackTrace");
        }
    }
}
