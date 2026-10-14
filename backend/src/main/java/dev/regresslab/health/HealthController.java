package dev.regresslab.health;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    // Liveness only. Database readiness is /actuator/health/readiness.
    @GetMapping("/api/v1/health")
    public Health health() {
        return new Health("UP", "regresslab-api");
    }

    public record Health(String status, String service) {}
}
