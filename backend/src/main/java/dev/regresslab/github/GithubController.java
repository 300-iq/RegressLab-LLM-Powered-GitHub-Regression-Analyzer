package dev.regresslab.github;
import dev.regresslab.github.GithubDtos.*;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/v1")
public class GithubController {
    private final GithubImportService service;
    private final GithubPersistence persistence;
    public GithubController(GithubImportService service,GithubPersistence persistence) { this.service=service; this.persistence=persistence; }
    @PostMapping(value="/dev/github-imports",consumes="application/json")
    public ResponseEntity<ImportPair> ingest(@Valid @RequestBody ImportRequest request) {
        var result=service.ingest(request);
        return ResponseEntity.status(result.baseline().replayed() && result.candidate().replayed() ? 200 : 201).body(result);
    }
    @GetMapping("/runs/{id}/github-audit") public GithubPersistence.Audit audit(@PathVariable UUID id) { return persistence.audit(id); }
}
