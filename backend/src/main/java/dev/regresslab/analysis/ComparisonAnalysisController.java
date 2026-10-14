package dev.regresslab.analysis;

import dev.regresslab.analysis.AnalysisDtos.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import dev.regresslab.domain.ApiDtos.Page;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class ComparisonAnalysisController {
    private final ComparisonAnalysisService service;
    public ComparisonAnalysisController(ComparisonAnalysisService service) { this.service = service; }
    @PostMapping(value = "/comparisons/{id}/analysis", consumes = "application/json")
    public ResponseEntity<AnalysisDto> compute(@PathVariable UUID id, @Valid @RequestBody Request request) {
        var result = service.compute(id, request);
        return ResponseEntity.ok().location(URI.create("/api/v1/comparison-analyses/" + result.id())).body(result);
    }
    @GetMapping("/comparison-analyses/{id}")
    public AnalysisDto get(@PathVariable UUID id) { return service.get(id); }
    @GetMapping("/comparisons/{id}/analyses")
    public Page<AnalysisDto> list(@PathVariable UUID id, @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int page,
                                 @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(id, page, size);
    }
}
