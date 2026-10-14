package dev.regresslab.ingestion;

import dev.regresslab.domain.ApiDtos.ImportResult;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class IngestionController {
    private final ArtifactValidator validator;
    private final IngestionService service;
    public IngestionController(ArtifactValidator validator, IngestionService service) { this.validator = validator; this.service = service; }
    @PostMapping(value = "/api/v1/dev/artifacts", consumes = "application/json")
    public ResponseEntity<ImportResult> ingest(@RequestBody byte[] bytes) {
        var result = service.ingest(validator.parse(bytes), bytes);
        return ResponseEntity.status(result.replayed() ? 200 : 201)
                .location(URI.create("/api/v1/runs/" + result.runId())).body(result);
    }
}
