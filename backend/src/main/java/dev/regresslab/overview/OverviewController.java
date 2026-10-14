package dev.regresslab.overview;

import dev.regresslab.domain.ApiDtos.OverviewDto;
import dev.regresslab.results.ResultsService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OverviewController {
    private final ResultsService service;

    public OverviewController(ResultsService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/overview")
    public OverviewDto overview() {
        return service.overview();
    }

}
