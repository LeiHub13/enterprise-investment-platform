package com.panyue;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

class HealthControllerTest {
    @Test
    void readyReturnsUpWhenMysqlResponds() {
        JdbcTemplate jdbc = new JdbcTemplate() {
            @Override
            public <T> T queryForObject(String sql, Class<T> requiredType) {
                assertEquals("SELECT 1", sql);
                return requiredType.cast(1);
            }
        };
        ResponseEntity<Map<String, String>> result = new HealthController(jdbc).ready();
        assertEquals(HttpStatus.OK, result.getStatusCode());
        assertEquals("UP", result.getBody().get("database"));
    }

    @Test
    void readyDoesNotExposeDatabaseFailure() {
        JdbcTemplate jdbc = new JdbcTemplate() {
            @Override
            public <T> T queryForObject(String sql, Class<T> requiredType) {
                throw new DataAccessResourceFailureException("private connection details");
            }
        };
        ResponseEntity<Map<String, String>> result = new HealthController(jdbc).ready();
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, result.getStatusCode());
        assertEquals(Map.of("status", "DOWN", "database", "DOWN"), result.getBody());
    }
}
