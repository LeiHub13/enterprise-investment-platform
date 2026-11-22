package com.enterprise.investment.collector;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Stable content comparison; JSON object key order and numeric scale do not create false updates. */
final class SnapshotFingerprint {
    private SnapshotFingerprint() {}

    record Snapshot(String title, String summary, JsonNode rawPayload, LocalDate publishedAt,
                    LocalDate observedAt, String sourceUrl, int strength, BigDecimal confidence,
                    LocalDateTime expiresAt) {}

    static String hash(ObjectMapper mapper, Snapshot snapshot) {
        Map<String, Object> values = new TreeMap<>();
        values.put("title", snapshot.title());
        values.put("summary", snapshot.summary());
        values.put("rawPayload", canonical(snapshot.rawPayload()));
        values.put("publishedAt", snapshot.publishedAt() == null ? null : snapshot.publishedAt().toString());
        values.put("observedAt", snapshot.observedAt() == null ? null : snapshot.observedAt().toString());
        values.put("sourceUrl", snapshot.sourceUrl());
        values.put("strength", snapshot.strength());
        values.put("confidence", snapshot.confidence() == null ? null : snapshot.confidence().stripTrailingZeros());
        values.put("expiresAt", snapshot.expiresAt() == null ? null : snapshot.expiresAt().truncatedTo(ChronoUnit.SECONDS).toString());
        try {
            return sha256(mapper.writeValueAsString(values));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("无法计算采集快照指纹", error);
        }
    }

    private static Object canonical(JsonNode node) {
        if (node == null || node.isNull()) return null;
        if (node.isObject()) {
            Map<String, Object> result = new TreeMap<>();
            node.fields().forEachRemaining(entry -> result.put(entry.getKey(), canonical(entry.getValue())));
            return result;
        }
        if (node.isArray()) {
            var result = new ArrayList<>();
            node.forEach(item -> result.add(canonical(item)));
            return result;
        }
        if (node.isNumber()) return node.decimalValue().stripTrailingZeros();
        if (node.isBoolean()) return node.booleanValue();
        return node.asText();
    }

    static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }
}
