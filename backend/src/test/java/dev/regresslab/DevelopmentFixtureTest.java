package dev.regresslab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.regresslab.ingestion.ArtifactValidator;
import dev.regresslab.domain.ArtifactDtos.DataOrigin;
import jakarta.validation.Validation;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DevelopmentFixtureTest {
    @Test void checkedInFixturesPassBackendSemanticValidationAndStayLabeled() throws Exception {
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        var validator = new ArtifactValidator(mapper, Validation.buildDefaultValidatorFactory().getValidator());
        for (String name : new String[]{"demo-baseline.v2.json", "demo-candidate.v2.json"}) {
            var artifact = validator.parse(Files.readAllBytes(Path.of("fixtures", name)));
            assertThat(artifact.dataOrigin()).isEqualTo(DataOrigin.DEMO);
            assertThat(artifact.run().environment().cpu()).startsWith("DEMO");
            assertThat(artifact.benchmarks()).hasSize(2);
        }
    }
}
