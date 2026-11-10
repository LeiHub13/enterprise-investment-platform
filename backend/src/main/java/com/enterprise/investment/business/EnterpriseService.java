package com.enterprise.investment.business;

import com.enterprise.investment.mapper.AuditMapper;
import com.enterprise.investment.mapper.EnterpriseMapper;
import com.enterprise.investment.mapper.EnterpriseVerificationMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import static com.enterprise.investment.business.Rows.contains;
import static com.enterprise.investment.business.Rows.jdbcValue;
import static com.enterprise.investment.business.Rows.params;
import static com.enterprise.investment.business.Rows.trimmed;
import static com.enterprise.investment.business.Rows.view;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
class EnterpriseService {
    private static final String ENTITY = "ENTERPRISE";
    private final EnterpriseMapper enterprises;
    private final AuditMapper audits;
    private final ObjectMapper objectMapper;
    private final RegionCatalog regions;
    private final EnterpriseVerificationMapper verifications;

    EnterpriseService(EnterpriseMapper enterprises, AuditMapper audits, ObjectMapper objectMapper,
                      RegionCatalog regions, EnterpriseVerificationMapper verifications) {
        this.enterprises = enterprises;
        this.audits = audits;
        this.objectMapper = objectMapper;
        this.regions = regions;
        this.verifications = verifications;
    }

    PageResult list(String keyword, String provinceCode, String cityCode,
                    String industryCode, String businessStatus, int page, int size) {
        long offset = Paging.offset(page, size);
        String normalized = trimmed(keyword) == null ? null : contains(normalizeName(keyword));
        var province = regions.variants(provinceCode, "province", null);
        var city = regions.variants(cityCode, "city", provinceCode);
        String industry = trimmed(industryCode);
        String status = trimmed(businessStatus);
        long total = enterprises.count(normalized, province, city, industry, status);
        var items = enterprises.list(normalized, province, city, industry, status, size, offset)
                .stream().map(Rows::view).toList();
        return new PageResult(items, total);
    }

    Map<String, Object> get(long id) {
        Map<String, Object> company = view(find(id, false));
        Map<String, Object> verification = verifications.latestReal(id);
        if (verification == null) {
            company.put("verificationStatus", "PENDING");
        } else {
            Map<String, Object> latest = view(verification);
            company.put("verificationStatus", latest.get("verificationStatus"));
            company.put("verificationSource", latest.get("source"));
            company.put("verificationAt", latest.get("verifiedAt"));
            company.put("verificationReason", latest.get("reason"));
            company.put("verificationFresh", latest.get("verificationFresh"));
        }
        return company;
    }

    @Transactional
    public Map<String, Object> create(EnterpriseInput input, long actor) {
        long source = sourceId("MANUAL");
        long id = insert(values(input), source);
        recordSource(source, id, null, null, input, null);
        audits.record(actor, "CREATE", ENTITY, id);
        return get(id);
    }

    @Transactional
    public Map<String, Object> replace(long id, EnterpriseInput input, long actor) {
        var old = find(id, true);
        var changes = values(input);
        var updateArgs = params("id", id);
        updateArgs.putAll(changes);
        try {
            enterprises.update(updateArgs);
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
                enterprises.insertChange(id, sourceRecord, field,
                        Objects.toString(jdbcValue(old.get(field)), null),
                        Objects.toString(jdbcValue(updated.get(field)), null), actor);
            }
            audits.record(actor, "UPDATE", ENTITY, id);
        }
        return view(updated);
    }

    @Transactional
    public void delete(long id, long actor) {
        find(id, true);
        enterprises.delete(id);
        audits.record(actor, "DELETE", ENTITY, id);
    }

    long importRow(EnterpriseInput input, long source, long batch, int row, Object raw, long actor) {
        var fields = values(input);
        fields.put("province_variants", regions.variants(input.provinceCode(), "province", null));
        fields.put("city_variants", regions.variants(input.cityCode(), "city", input.provinceCode()));
        fields.put("district_variants", regions.variants(input.districtCode(), "district", input.cityCode()));
        // Serialize the candidate check across Excel uploads, including records with no credit code.
        if (enterprises.lockDataSource(source) == null) {
            throw new ResponseStatusException(NOT_FOUND, "未配置数据来源");
        }
        if (enterprises.countCandidates(fields) > 0) {
            throw new ResponseStatusException(CONFLICT, "存在同名候选企业，请核对统一社会信用代码");
        }
        long id = insert(fields, source);
        recordSource(source, id, batch, Integer.toString(row), raw, null);
        audits.record(actor, "IMPORT", ENTITY, id);
        return id;
    }

    long sourceId(String type) {
        String name = switch (type) {
            case "MANUAL" -> "人工录入";
            case "EXCEL" -> "Excel 批量导入";
            default -> throw new IllegalArgumentException("Unsupported source type: " + type);
        };
        Long id = enterprises.findDataSource(type, name);
        if (id == null) {
            throw new ResponseStatusException(NOT_FOUND, "未配置数据来源");
        }
        return id;
    }

    long recordSource(long source, Long enterprise, Long batch, String row, Object raw, String error) {
        String payload = json(raw);
        var record = params("source", source, "enterprise", enterprise, "batch", batch, "row", row,
                "entityKey", enterprise == null ? null : Long.toString(enterprise), "payload", payload,
                "checksum", checksum(payload), "status", error == null ? "PARSED" : "FAILED",
                "error", error, "fetchedAt", LocalDateTime.now());
        enterprises.insertSourceRecord(record);
        return ((Number) record.get("id")).longValue();
    }

    private Map<String, Object> find(long id, boolean lock) {
        var row = lock ? enterprises.findByIdForUpdate(id) : enterprises.findById(id);
        return Rows.required(row, "企业不存在");
    }

    private long insert(Map<String, Object> values, long source) {
        values.put("source_id", source);
        try {
            enterprises.insert(values);
        } catch (DuplicateKeyException e) {
            throw duplicate(e);
        }
        return ((Number) values.get("id")).longValue();
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

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法保存数据来源记录", e);
        }
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
