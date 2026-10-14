package dev.regresslab.results;

import dev.regresslab.analysis.ComparisonService;
import dev.regresslab.domain.ApiDtos.*;
import dev.regresslab.domain.ArtifactDtos.DataOrigin;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ResultsController {
    private final ResultsService service;
    private final ComparisonService comparisons;
    public ResultsController(ResultsService service, ComparisonService comparisons) { this.service = service; this.comparisons = comparisons; }
    @GetMapping("/repositories") public List<RepositoryDto> repositories() { return service.repositories(); }
    @GetMapping("/repositories/{id}/pull-requests")
    public Page<PullRequestDto> prs(@PathVariable UUID id, @RequestParam(defaultValue = "REAL") DataOrigin origin,
                                  @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
                                  @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) { return service.prs(id, origin, page, size); }
    @GetMapping("/repositories/{id}/runs")
    public Page<RunDto> runs(@PathVariable UUID id, @RequestParam(defaultValue = "REAL") DataOrigin origin,
                            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
                            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) { return service.runs(id, origin, page, size); }
    @GetMapping("/pull-requests/{id}")
    public PrDetail pr(@PathVariable UUID id, @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
                       @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) { return service.pr(id, page, size); }
    @GetMapping("/pull-requests/{id}/runs")
    public Page<RunDto> prRuns(@PathVariable UUID id, @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
                              @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) { return service.prRuns(id, page, size); }
    @GetMapping("/runs/{id}") public RunDetail run(@PathVariable UUID id) { return service.run(id); }
    @GetMapping("/runs/{runId}/cases/{caseId}/samples")
    public SamplePage samples(@PathVariable UUID runId, @PathVariable UUID caseId,
                              @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
                              @RequestParam(defaultValue = "100") @Min(1) @Max(100) int size) { return service.samples(runId, caseId, page, size); }
    @GetMapping("/cases/{caseId}/history")
    public Page<HistoryPoint> history(@PathVariable UUID caseId, @RequestParam(defaultValue = "REAL") DataOrigin origin,
                                    @RequestParam(required = false) @Pattern(regexp = "^[0-9a-f]{64}$") String environmentSha256,
                                    @RequestParam(required = false) @Pattern(regexp = "^[0-9a-f]{64}$") String configurationSha256,
                                    @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
                                    @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) { return service.history(caseId, origin, environmentSha256, configurationSha256, page, size); }
    @PostMapping(value = "/comparisons", consumes = "application/json")
    public ResponseEntity<ComparisonDto> compute(@Valid @RequestBody ComparisonRequest input) {
        var result = comparisons.compute(input);
        return ResponseEntity.ok().location(URI.create("/api/v1/comparisons/" + result.id())).body(result);
    }
    @GetMapping("/comparisons/{id}") public ComparisonDto comparison(@PathVariable UUID id) { return service.comparison(id); }
    @GetMapping("/pull-requests/{id}/comparisons")
    public Page<ComparisonDto> comparisons(@PathVariable UUID id, @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
                                          @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) { return service.comparisons(id, page, size); }
}
