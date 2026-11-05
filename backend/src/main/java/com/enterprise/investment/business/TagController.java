package com.enterprise.investment.business;

import com.enterprise.investment.security.AccountPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import static com.enterprise.investment.business.BusinessData.params;
import static com.enterprise.investment.business.BusinessData.trimmed;

@RestController
public class TagController {
    private final BusinessData db;

    TagController(BusinessData db) {
        this.db = db;
    }

    @GetMapping("/api/tags")
    public BusinessData.Page list(@RequestParam(required = false) String keyword,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        return db.page("id, tag_name, tag_type", "FROM ent_tag", """
                 WHERE (:keyword IS NULL OR tag_name LIKE :keyword ESCAPE '!')
                """, "tag_name, id",
                params("keyword", trimmed(keyword) == null ? null : BusinessData.contains(keyword)), page, size);
    }

    @PostMapping("/api/tags")
    @PreAuthorize("hasAnyRole('ADMIN','DATA_STEWARD')")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody TagInput input,
                                      @AuthenticationPrincipal AccountPrincipal actor) {
        String name = trimmed(input.tagName());
        String type = trimmed(input.tagType());
        if (name == null || type == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写标签名称和类型");
        }
        long id = db.insert("INSERT INTO ent_tag (tag_name, tag_type) VALUES (:name, :type)",
                params("name", name, "type", type));
        db.audit(actor.id(), "CREATE", "TAG", id);
        return BusinessData.view(db.one("SELECT id, tag_name, tag_type FROM ent_tag WHERE id = :id",
                params("id", id), "标签不存在"));
    }

    @GetMapping("/api/enterprises/{id}/tags")
    public List<Map<String, Object>> assigned(@PathVariable long id) {
        db.one("SELECT id FROM ent_enterprise WHERE id = :id", params("id", id), "企业不存在");
        return db.query("""
                SELECT t.id, t.tag_name, t.tag_type
                FROM ent_tag t JOIN ent_enterprise_tag et ON et.tag_id = t.id
                WHERE et.enterprise_id = :id ORDER BY t.tag_name, t.id
                """, params("id", id)).stream().map(BusinessData::view).toList();
    }

    @PutMapping("/api/enterprises/{id}/tags")
    @PreAuthorize("hasAnyRole('ADMIN','DATA_STEWARD')")
    @Transactional
    public List<Map<String, Object>> replace(@PathVariable long id, @Valid @RequestBody TagsInput input,
                                             @AuthenticationPrincipal AccountPrincipal actor) {
        db.one("SELECT id FROM ent_enterprise WHERE id = :id FOR UPDATE", params("id", id), "企业不存在");
        List<Long> tags = input.tagIds();
        if (new HashSet<>(tags).size() != tags.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标签不能重复");
        }
        if (!tags.isEmpty() && db.query("SELECT id FROM ent_tag WHERE id IN (:tags) FOR SHARE",
                params("tags", tags)).size() != tags.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "所选标签不存在");
        }
        db.update("DELETE FROM ent_enterprise_tag WHERE enterprise_id = :id", params("id", id));
        for (Long tag : tags) {
            db.update("INSERT INTO ent_enterprise_tag (enterprise_id, tag_id) VALUES (:enterprise, :tag)",
                    params("enterprise", id, "tag", tag));
        }
        db.audit(actor.id(), "UPDATE", "ENTERPRISE_TAG", id);
        return assigned(id);
    }

    record TagInput(@NotBlank @Size(max = 100) String tagName,
                    @NotBlank @Size(max = 50) String tagType) {}

    record TagsInput(@NotNull @Size(max = 100) List<@NotNull @Positive Long> tagIds) {}
}
