package com.enterprise.investment.business;

import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static com.enterprise.investment.business.BusinessData.params;

@RestController
@RequestMapping("/api")
public class GovernanceController {
    private final BusinessData db;

    GovernanceController(BusinessData db) {
        this.db = db;
    }

    @GetMapping("/enterprises/{id}/history")
    public BusinessData.Page history(@PathVariable long id,
                                     @RequestParam(defaultValue = "1") int page,
                                     @RequestParam(defaultValue = "20") int size) {
        db.one("SELECT id FROM ent_enterprise WHERE id = :id", params("id", id), "企业不存在");
        return db.page("""
                c.id, c.field_name, c.old_value, c.new_value, c.created_at,
                u.username AS changed_by_username, s.source_name
                """, """
                 FROM ent_enterprise_change c
                 LEFT JOIN sys_user u ON u.id = c.changed_by
                 LEFT JOIN data_source_record r ON r.id = c.source_record_id
                 LEFT JOIN data_source s ON s.id = r.source_id
                """, " WHERE c.enterprise_id = :id", "c.id DESC", params("id", id), page, size);
    }

    @GetMapping("/import-batches")
    @PreAuthorize("hasAnyRole('ADMIN','DATA_STEWARD')")
    public BusinessData.Page imports(@RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "20") int size) {
        return db.page("""
                b.id, b.file_name, b.total_count, b.success_count, b.fail_count, b.status,
                b.created_at, b.finished_at, u.username AS created_by_username, s.source_name
                """, """
                 FROM data_import_batch b
                 LEFT JOIN sys_user u ON u.id = b.created_by
                 JOIN data_source s ON s.id = b.source_id
                """, "", "b.id DESC", params(), page, size);
    }

    @GetMapping("/import-batches/{id}/rows")
    @PreAuthorize("hasAnyRole('ADMIN','DATA_STEWARD')")
    public BusinessData.Page importRows(@PathVariable long id,
                                       @RequestParam(defaultValue = "false") boolean failuresOnly,
                                       @RequestParam(defaultValue = "1") int page,
                                       @RequestParam(defaultValue = "20") int size) {
        db.one("SELECT id FROM data_import_batch WHERE id = :id", params("id", id), "导入批次不存在");
        return db.page("""
                r.id, r.external_id AS `row_number`, r.enterprise_id, r.parse_status, r.parse_error,
                JSON_UNQUOTE(JSON_EXTRACT(r.raw_payload, '$.name')) AS enterprise_name,
                JSON_UNQUOTE(JSON_EXTRACT(r.raw_payload, '$.uscc')) AS uscc, r.created_at
                """, "FROM data_source_record r", """
                 WHERE r.import_batch_id = :id AND (:failuresOnly = FALSE OR r.parse_status = 'FAILED')
                """, "CAST(r.external_id AS UNSIGNED), r.id", params("id", id, "failuresOnly", failuresOnly),
                page, size);
    }
}
