package com.enterprise.investment.business;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.enterprise.investment.business.BusinessData.*;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;

@Service
class EnterpriseService {
    private static final String ENTITY = "ENTERPRISE";
    private static final String FIELDS = """
            id, uscc, name, name_normalized, enterprise_type, province_code, city_code, district_code,
            industry_code, industry_name, registered_capital, established_date, business_status,
            registered_address, business_scope, website, source_id, last_verified_at, created_at, updated_at
            """;
    private final BusinessData db;

    EnterpriseService(BusinessData db) {
        this.db = db;
    }

    BusinessData.Page list(String keyword, String provinceCode, String cityCode,
                           String industryCode, String businessStatus, int page, int size) {
        var args = params("keyword", trimmed(keyword) == null ? null : contains(normalizeName(keyword)),
                "province", trimmed(provinceCode), "city", trimmed(cityCode),
                "industry", trimmed(industryCode), "status", trimmed(businessStatus));
        return db.page(FIELDS, "FROM ent_enterprise", """
                 WHERE (:keyword IS NULL OR name_normalized LIKE :keyword ESCAPE '!'
                         OR CONVERT(uscc USING utf8mb4) LIKE :keyword ESCAPE '!')
                   AND (:province IS NULL OR province_code = :province)
                   AND (:city IS NULL OR city_code = :city)
                   AND (:industry IS NULL OR industry_code = :industry)
                   AND (:status IS NULL OR business_status = :status)
                """, "id DESC", args, page, size);
    }

    Map<String, Object> get(long id) {
        return view(find(id, false));
    }

    @Transactional
    public Map<String, Object> create(EnterpriseInput input, long actor) {
        long source = sourceId("MANUAL");
        long id = insert(values(input), source);
        recordSource(source, id, null, null, input, null);
        db.audit(actor, "CREATE", ENTITY, id);
        return get(id);
    }

    @Transactional
    public Map<String, Object> replace(long id, EnterpriseInput input, long actor) {
        var old = find(id, true);
        var changes = values(input);
        var assignment = changes.keySet().stream().map(field -> field + " = :" + field)
                .collect(Collectors.joining(", "));
        var updateArgs = params("id", id);
        updateArgs.putAll(changes);
        try {
            db.update("UPDATE ent_enterprise SET " + assignment + " WHERE id = :id", updateArgs);
        } catch (DuplicateKeyException e) {
            throw duplicate(e);
        }
        var updated = find(id, false);
        var changedFields = changes.keySet().stream()
                .filter(field -> !Objects.equals(jdbcValue(old.get(field)), jdbcValue(updated.get(field))))
                .toList();
        if (!changedFields.isEmpty()) {
            long sourceRecord = recordSource(sourceId("MANUAL"), id, null, null, input, null);
            for (String field : changedFields) {
                db.update("""
                        INSERT INTO ent_enterprise_change
                          (enterprise_id, source_record_id, field_name, old_value, new_value, changed_by)
                        VALUES (:enterprise, :sourceRecord, :field, :oldValue, :newValue, :actor)
                        """, params("enterprise", id, "sourceRecord", sourceRecord, "field", field,
                        "oldValue", Objects.toString(jdbcValue(old.get(field)), null),
                        "newValue", Objects.toString(jdbcValue(updated.get(field)), null), "actor", actor));
            }
            db.audit(actor, "UPDATE", ENTITY, id);
        }
        return view(updated);
    }

    @Transactional
    public void delete(long id, long actor) {
        find(id, true);
        db.update("DELETE FROM ent_enterprise WHERE id = :id", params("id", id));
        db.audit(actor, "DELETE", ENTITY, id);
    }

    long importRow(EnterpriseInput input, long source, long batch, int row, Object raw, long actor) {
        var fields = values(input);
        // Serialize the candidate check across Excel uploads, including records with no credit code.
        db.one("SELECT id FROM data_source WHERE id = :id AND enabled = TRUE FOR UPDATE",
                params("id", source), "未配置数据来源");
        if (hasCandidate(fields)) {
            throw new ResponseStatusException(CONFLICT, "存在同名候选企业，请核对统一社会信用代码");
        }
        long id = insert(fields, source);
        recordSource(source, id, batch, Integer.toString(row), raw, null);
        db.audit(actor, "IMPORT", ENTITY, id);
        return id;
    }

    long sourceId(String type) {
        String name = switch (type) {
            case "MANUAL" -> "人工录入";
            case "EXCEL" -> "Excel 批量导入";
            default -> throw new IllegalArgumentException("Unsupported source type: " + type);
        };
        return ((Number) db.one("""
                SELECT id FROM data_source WHERE source_type = :type AND source_name = :name AND enabled = TRUE
                """,
                params("type", type, "name", name), "未配置数据来源").get("id")).longValue();
    }

    long recordSource(long source, Long enterprise, Long batch, String row, Object raw, String error) {
        String payload = db.json(raw);
        return db.insert("""
                INSERT INTO data_source_record
                  (source_id, enterprise_id, import_batch_id, external_id, entity_key, raw_payload,
                   checksum, parse_status, parse_error, fetched_at)
                VALUES
                  (:source, :enterprise, :batch, :row, :entityKey, :payload,
                   :checksum, :status, :error, :fetchedAt)
                """, params("source", source, "enterprise", enterprise, "batch", batch, "row", row,
                "entityKey", enterprise == null ? null : Long.toString(enterprise), "payload", payload,
                "checksum", checksum(payload), "status", error == null ? "PARSED" : "FAILED",
                "error", error, "fetchedAt", LocalDateTime.now()));
    }

    private Map<String, Object> find(long id, boolean lock) {
        return db.one("SELECT " + FIELDS + " FROM ent_enterprise WHERE id = :id" + (lock ? " FOR UPDATE" : ""),
                params("id", id), "企业不存在");
    }

    private long insert(Map<String, Object> values, long source) {
        values.put("source_id", source);
        String columns = String.join(", ", values.keySet());
        String placeholders = values.keySet().stream().map(field -> ":" + field).collect(Collectors.joining(", "));
        try {
            return db.insert("INSERT INTO ent_enterprise (" + columns + ") VALUES (" + placeholders + ")", values);
        } catch (DuplicateKeyException e) {
            throw duplicate(e);
        }
    }

    private boolean hasCandidate(Map<String, Object> fields) {
        return !db.query("""
                SELECT id FROM ent_enterprise
                WHERE name_normalized = :name_normalized
                  AND (:uscc IS NULL OR uscc IS NULL)
                  AND (:province_code IS NULL OR province_code IS NULL OR province_code = :province_code)
                  AND (:city_code IS NULL OR city_code IS NULL OR city_code = :city_code)
                  AND (:district_code IS NULL OR district_code IS NULL OR district_code = :district_code)
                LIMIT 1
                """, fields).isEmpty();
    }

    private static Map<String, Object> values(EnterpriseInput input) {
        String name = trimmed(input.name());
        if (name == null) {
            throw new ResponseStatusException(BAD_REQUEST, "请填写企业名称");
        }
        String normalized = normalizeName(name);
        if (normalized.isBlank() || normalized.length() > 255) {
            throw new ResponseStatusException(BAD_REQUEST, "标准化后的企业名称须包含 1 到 255 个字符");
        }
        String uscc = trimmed(input.uscc());
        if (uscc != null) {
            uscc = Normalizer.normalize(uscc, Normalizer.Form.NFKC)
                    .replaceAll("(?U)\\s+", "").toUpperCase(Locale.ROOT);
            if (!uscc.matches("[0-9A-HJ-NP-RTUWXY]{18}")) {
                throw new ResponseStatusException(BAD_REQUEST, "统一社会信用代码须为 18 位有效字符");
            }
        }
        return params("uscc", uscc, "name", name, "name_normalized", normalized,
                "enterprise_type", trimmed(input.enterpriseType()), "province_code", trimmed(input.provinceCode()),
                "city_code", trimmed(input.cityCode()), "district_code", trimmed(input.districtCode()),
                "industry_code", trimmed(input.industryCode()), "industry_name", trimmed(input.industryName()),
                "registered_capital", input.registeredCapital(), "established_date", input.establishedDate(),
                "business_status", trimmed(input.businessStatus()), "registered_address", trimmed(input.registeredAddress()),
                "business_scope", trimmed(input.businessScope()), "website", trimmed(input.website()),
                "last_verified_at", input.lastVerifiedAt());
    }

    private static String normalizeName(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFKC).replaceAll("(?U)\\s+", " ").strip();
    }

    private static String checksum(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ResponseStatusException duplicate(DuplicateKeyException e) {
        return new ResponseStatusException(CONFLICT, "统一社会信用代码已存在，请检查原有档案", e);
    }
}
