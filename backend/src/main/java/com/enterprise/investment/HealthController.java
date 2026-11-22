package com.enterprise.investment;

import java.util.Map;

import com.enterprise.investment.mapper.HealthMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class HealthController {
    private final HealthMapper health;

    HealthController(HealthMapper health) {
        this.health = health;
    }

    @GetMapping("/api/health")
    Map<String, String> health() {
        try {
            health.ping();
            return Map.of("status", "UP");
        } catch (DataAccessException error) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "数据库暂不可用");
        }
    }
}
