package dev.regresslab.ai;

import dev.regresslab.ai.AiDtos.*;
import dev.regresslab.domain.ApiDtos.Page;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class AiInvestigationController {
    private final AiInvestigationService service;
    public AiInvestigationController(AiInvestigationService service) { this.service=service; }
    @PostMapping(value="/comparisons/{id}/ai-investigations",consumes="application/json")
    public ResponseEntity<JobDto> generate(@PathVariable UUID id,@Valid @RequestBody Request request) {
        var job=service.submit(id,request);
        return ResponseEntity.accepted().location(URI.create("/api/v1/ai-investigation-jobs/"+job.id())).body(job);
    }
    @GetMapping("/ai-investigation-jobs/{id}") public JobDto job(@PathVariable UUID id) { return service.job(id); }
    @GetMapping("/comparisons/{id}/ai-investigation-jobs")
    public Page<JobDto> jobs(@PathVariable UUID id,@RequestParam(defaultValue="0") @Min(0) @Max(100000) int page,
                            @RequestParam(defaultValue="20") @Min(1) @Max(100) int size) { return service.jobs(id,page,size); }
    @GetMapping("/ai-investigations/{id}") public ReportDto get(@PathVariable UUID id) { return service.get(id); }
    @GetMapping("/comparisons/{id}/ai-investigations")
    public Page<ReportDto> list(@PathVariable UUID id,@RequestParam(defaultValue="0") @Min(0) @Max(100000) int page,
                                @RequestParam(defaultValue="20") @Min(1) @Max(100) int size) { return service.list(id,page,size); }
}
