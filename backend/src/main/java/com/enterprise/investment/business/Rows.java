package com.enterprise.investment.business;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.NOT_FOUND;

public final class Rows {
    private Rows() {
    }

    public static Map<String, Object> required(Map<String, Object> row, String missing) {
        if (row == null) {
            throw new ResponseStatusException(NOT_FOUND, missing);
        }
        return row;
    }

    public static Map<String, Object> params(Object... entries) {
        var result = new LinkedHashMap<String, Object>();
        for (int index = 0; index < entries.length; index += 2) {
            result.put((String) entries[index], entries[index + 1]);
        }
        return result;
    }

    public static Map<String, Object> view(Map<String, Object> row) {
        var result = new LinkedHashMap<String, Object>();
        row.forEach((name, value) -> {
            Object jsonValue = value instanceof BigDecimal amount ? amount.toPlainString() : jdbcValue(value);
            result.put(camelCase(name), jsonValue);
        });
        return result;
    }

    public static Object jdbcValue(Object value) {
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof Date date) {
            return date.toLocalDate();
        }
        return value;
    }

    public static String trimmed(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    public static String contains(String value) {
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
}
