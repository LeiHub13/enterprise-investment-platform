package com.panyue;

import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/api/health")
    public Map<String, String> live() {
        return Map.of("status", "UP", "service", "panyue-business-api");
    }

    @GetMapping("/api/health/ready")
    public ResponseEntity<Map<String, String>> ready() {
        try {
            Integer value = jdbc.queryForObject("SELECT 1", Integer.class);
            if (value != null && value == 1) {
                return ResponseEntity.ok(Map.of("status", "UP", "database", "UP"));
            }
        } catch (DataAccessException ignored) {
            // Do not expose connection details in an HTTP response.
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("status", "DOWN", "database", "DOWN"));
    }
}
