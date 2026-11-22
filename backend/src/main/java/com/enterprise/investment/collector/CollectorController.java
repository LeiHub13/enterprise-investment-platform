package com.enterprise.investment.collector;

import com.enterprise.investment.business.Rows;
import com.enterprise.investment.mapper.DataMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/collector")
public class CollectorController {
    private static final List<String> SOURCES = List.of("QCC", "58", "MANUAL");
    private static final List<String> TYPES = List.of("RECRUITMENT", "RENTAL");
    private static final int PAYLOAD_LIMIT = 100_000;
    private final DataMapper data;
    private final ObjectMapper json;

    CollectorController(DataMapper data, ObjectMapper json) {
        this.data = data;
        this.json = json;
    }

    record RecordInput(
            @NotBlank @Size(max = 24) String source,
            @NotBlank @Size(max = 16) String recordType,
            @Size(max = 160) String externalId,
            @NotNull @Positive Long enterpriseId,
            @NotBlank @Size(max = 255) String title,
            @Size(max = 15000) String summary,
            @Size(max = 512) String sourceUrl,
            JsonNode rawPayload,
            LocalDate publishedAt,
            LocalDate observedAt,
            @Min(1) @Max(3) int strength,
            @DecimalMin("0") @DecimalMax("1") double confidence,
            Instant expiresAt,
            Instant sourceUpdatedAt) {
        RecordInput(String source, String recordType, String externalId, Long enterpriseId, String title,
                    String summary, String sourceUrl, JsonNode rawPayload, LocalDate publishedAt,
                    LocalDate observedAt, int strength, double confidence) {
            this(source, recordType, externalId, enterpriseId, title, summary, sourceUrl, rawPayload,
                    publishedAt, observedAt, strength, confidence, null, null);
        }
    }

    record ExpireInput(@Min(1) @Max(100) int limit) {}

    @PostMapping("/records")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> ingest(@Valid @RequestBody RecordInput input) {
        String source = input.source().trim().toUpperCase();
        String type = input.recordType().trim().toUpperCase();
        if (!SOURCES.contains(source) || !TYPES.contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "来源或记录类型无效");
        }
        String raw = input.rawPayload() == null ? null : input.rawPayload().toString();
        if (raw != null && raw.length() > PAYLOAD_LIMIT) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "原始数据超过长度限制，请只保存获准使用的字段");
        }
        if (input.rawPayload() != null && input.rawPayload().has("demo") && !input.rawPayload().get("demo").isBoolean()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "演示数据标记必须为布尔值");
        }
        if (input.sourceUpdatedAt() != null && input.sourceUpdatedAt().isAfter(Instant.now().plusSeconds(300))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "来源更新时间不能晚于当前时间超过五分钟");
        }
        if (data.findCompany(input.enterpriseId()) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "企业资源库中未找到该企业");
        }
        String externalId = input.externalId() == null ? "" : input.externalId().trim();
        String legacyHash = SnapshotFingerprint.sha256(source + "|" + externalId + "|" + type + "|" + input.enterpriseId());
        boolean demo = isDemo(input.rawPayload());
        String identity = SnapshotFingerprint.sha256(legacyHash + (demo ? "|DEMO" : "|LIVE"));
        LocalDateTime expires = localTime(input.expiresAt());
        LocalDateTime sourceUpdated = utcTime(input.sourceUpdatedAt());
        var snapshot = new SnapshotFingerprint.Snapshot(input.title().trim(), input.summary(), input.rawPayload(),
                input.publishedAt(), input.observedAt(), input.sourceUrl(), input.strength(),
                BigDecimal.valueOf(input.confidence()), expires);
        String fingerprint = SnapshotFingerprint.hash(json, snapshot);

        data.insertSourceCursor(identity, input.enterpriseId());
        Map<String, Object> cursor = data.lockSourceCursor(identity);
        if (cursor == null) throw new IllegalStateException("采集来源游标不存在");
        Long previousId = number(cursor.get("current_record_id"));
        int previousVersion = ((Number) cursor.get("current_version")).intValue();
        String previousFingerprint = (String) cursor.get("current_fingerprint");
        LocalDateTime previousUpdated = dateTime(cursor.get("source_updated_at"));

        if (previousId == null && previousVersion == 0) {
            Map<String, Object> legacy = data.findSnapshotByHash(legacyHash);
            if (legacy != null && legacy.get("identity_hash") == null && isDemo(readRaw(legacy.get("raw_payload"))) == demo) {
                previousId = number(legacy.get("id"));
                previousVersion = 1;
                previousFingerprint = SnapshotFingerprint.hash(json, legacySnapshot(legacy));
                data.attachLegacyIdentity(previousId, identity);
                data.updateSourceCursor(identity, previousId, previousVersion, previousFingerprint, null);
            }
        }
        if (previousId == null) {
            previousFingerprint = null;
            previousUpdated = null;
        }
        if (previousUpdated != null && sourceUpdated != null && sourceUpdated.isBefore(previousUpdated)) {
            return Map.of("created", false, "recordId", previousId, "changeType", "STALE");
        }
        if (previousId != null && fingerprint.equals(previousFingerprint)) {
            if (sourceUpdated != null && (previousUpdated == null || sourceUpdated.isAfter(previousUpdated))) {
                data.updateSourceCursor(identity, previousId, previousVersion, previousFingerprint, sourceUpdated);
            }
            return Map.of("created", false, "recordId", previousId, "changeType", "UNCHANGED");
        }
        if (previousUpdated != null && (sourceUpdated == null || sourceUpdated.equals(previousUpdated))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "来源更新时间缺失或相同，不能覆盖已排序的不同内容");
        }

        int version = previousVersion + 1;
        String change = previousId == null ? "NEW" : "UPDATED";
        if (previousId != null) data.supersedeRecord(previousId);
        boolean expired = expires != null && expires.isBefore(LocalDateTime.now());
        Map<String, Object> values = Rows.params("source", source, "externalId", externalId, "recordType", type,
                "enterpriseId", input.enterpriseId(), "title", input.title().trim(), "summary", input.summary(),
                "rawPayload", raw, "contentHash", SnapshotFingerprint.sha256("v2|" + identity + "|" + version),
                "publishedAt", input.publishedAt(), "validityStatus", expired ? "EXPIRED" : "VALID",
                "identity", identity, "version", version, "changeType", change);
        data.insertVersion(values);
        long recordId = ((Number) values.get("id")).longValue();
        data.insertSignal(input.enterpriseId(), type, input.title().trim(), input.summary(), source, input.sourceUrl(),
                input.observedAt(), input.strength(), recordId, input.confidence(), expires);
        data.updateSourceCursor(identity, recordId, version, fingerprint, sourceUpdated);
        data.insertOutbox(recordId, "COLLECTOR_RECORD_" + ("NEW".equals(change) ? "CREATED" : "UPDATED"));
        return Map.of("created", true, "recordId", recordId, "changeType", change, "version", version,
                "validityStatus", expired ? "EXPIRED" : "VALID");
    }

    @PostMapping("/records/expire")
    @Transactional
    public Map<String, Object> expire(@Valid @RequestBody ExpireInput input) {
        int expired = 0;
        for (long record : data.lockExpiringRecords(input.limit())) {
            if (data.expireRecord(record) == 1) {
                data.insertOutbox(record, "COLLECTOR_RECORD_EXPIRED");
                expired++;
            }
        }
        return Map.of("expired", expired);
    }

    @GetMapping("/companies/resolve")
    public Map<String, Object> resolve(@RequestParam(required = false) String uscc,
                                       @RequestParam(required = false) String name) {
        List<Map<String, Object>> companies = data.resolveCompany(trim(uscc), trim(name));
        if (companies.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "企业资源库中未找到该企业");
        }
        if (companies.size() != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "存在同名企业，请使用统一社会信用代码确认主体");
        }
        return Rows.view(companies.get(0));
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of("sources", data.sourceStatus().stream().map(Rows::view).toList());
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private JsonNode readRaw(Object value) {
        if (value == null) return null;
        try {
            return json.readTree(String.valueOf(value));
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(error);
        }
    }

    private SnapshotFingerprint.Snapshot legacySnapshot(Map<String, Object> row) {
        Object strength = row.get("strength");
        Object confidence = row.get("confidence");
        return new SnapshotFingerprint.Snapshot((String) row.get("title"), (String) row.get("summary"),
                readRaw(row.get("raw_payload")), date(row.get("published_at")), date(row.get("observed_at")),
                (String) row.get("source_url"), strength == null ? 0 : ((Number) strength).intValue(),
                confidence == null ? null : new BigDecimal(confidence.toString()), dateTime(row.get("expires_at")));
    }

    private static boolean isDemo(JsonNode raw) {
        if (raw == null || !raw.has("demo")) return false;
        return !List.of("false", "0").contains(raw.get("demo").asText().toLowerCase(Locale.ROOT));
    }
    private static Long number(Object value) { return value == null ? null : ((Number) value).longValue(); }
    private static LocalDateTime localTime(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneId.systemDefault()).truncatedTo(ChronoUnit.SECONDS);
    }
    private static LocalDateTime utcTime(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value, ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    }
    private static LocalDateTime dateTime(Object value) {
        return value == null ? null : value instanceof Timestamp timestamp ? timestamp.toLocalDateTime()
                : (LocalDateTime) value;
    }
    private static LocalDate date(Object value) {
        if (value == null) return null;
        if (value instanceof java.sql.Date date) return date.toLocalDate();
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime().toLocalDate();
        if (value instanceof LocalDate date) return date;
        return ((LocalDateTime) value).toLocalDate();
    }
}
