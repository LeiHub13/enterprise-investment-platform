package com.enterprise.investment.business;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Component
class BusinessData {
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    BusinessData(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    List<Map<String, Object>> query(String sql, Map<String, ?> args) {
        return jdbc.queryForList(sql, args);
    }

    Map<String, Object> one(String sql, Map<String, ?> args, String missing) {
        return query(sql, args).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, missing));
    }

    int update(String sql, Map<String, ?> args) {
        return jdbc.update(sql, args);
    }

    long insert(String sql, Map<String, ?> args) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(sql, new MapSqlParameterSource(args), keys, new String[]{"id"});
        return Objects.requireNonNull(keys.getKey()).longValue();
    }

    Page page(String fields, String from, String conditions, String order, Map<String, Object> args,
              int page, int size) {
        if (page < 1 || size < 1 || size > 100) {
            throw new ResponseStatusException(BAD_REQUEST, "页码从 1 开始，每页可查询 1 到 100 条");
        }
        String filtered = from + conditions;
        long total = Objects.requireNonNull(jdbc.queryForObject("SELECT COUNT(*) " + filtered, args, Long.class));
        args.put("limit", size);
        args.put("offset", (page - 1L) * size);
        var items = query("SELECT " + fields + " " + filtered + " ORDER BY " + order +
                " LIMIT :limit OFFSET :offset", args).stream().map(BusinessData::view).toList();
        return new Page(items, total);
    }

    void audit(long userId, String operation, String resourceType, long resourceId) {
        update("""
                INSERT INTO audit_log (user_id, operation, resource_type, resource_id)
                VALUES (:userId, :operation, :resourceType, :resourceId)
                """, params("userId", userId, "operation", operation, "resourceType", resourceType,
                "resourceId", resourceId));
    }

    void checkAssignable(Long id) {
        if (id != null && query("""
                SELECT id FROM sys_user
                WHERE id = :id AND enabled = TRUE
                  AND role IN ('ADMIN', 'INVESTMENT_MANAGER', 'INVESTMENT_OFFICER')
                FOR SHARE
                """, params("id", id)).isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "负责人必须是已启用的管理员或招商人员");
        }
    }

    String json(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法保存数据来源记录", e);
        }
    }

    static Map<String, Object> params(Object... entries) {
        var result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put((String) entries[index], entries[index + 1]);
        }
        return result;
    }

    static Map<String, Object> view(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        row.forEach((name, value) -> {
            Object jsonValue = switch (name) {
                case "registered_capital", "investment_min", "investment_max" ->
                        value instanceof BigDecimal amount ? amount.toPlainString() : value;
                default -> jdbcValue(value);
            };
            result.put(camelCase(name), jsonValue);
        });
        return result;
    }

    static Object jdbcValue(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof Date date) {
            return date.toLocalDate();
        }
        return value;
    }

    static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    static String contains(String value) {
        return "%" + value.strip().replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
    }

    private static String camelCase(String value) {
        var result = new StringBuilder();
        boolean uppercase = false;
        for (char character : value.toCharArray()) {
            if (character == '_') {
                uppercase = true;
            } else {
                result.append(uppercase ? Character.toUpperCase(character) : character);
                uppercase = false;
            }
        }
        return result.toString();
    }

    record Page(List<Map<String, Object>> items, long total) {}
}
