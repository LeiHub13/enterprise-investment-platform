package com.enterprise.investment.business;

import com.enterprise.investment.mapper.AuditMapper;
import com.enterprise.investment.mapper.TagMapper;
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

import static com.enterprise.investment.business.Rows.contains;
import static com.enterprise.investment.business.Rows.trimmed;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
public class TagController {
    private final TagMapper tags;
    private final AuditMapper audits;

    TagController(TagMapper tags, AuditMapper audits) {
        this.tags = tags;
        this.audits = audits;
    }

    @GetMapping("/api/tags")
    @PreAuthorize("@ss.hasPermission('enterprise:view')")
    public PageResult list(@RequestParam(required = false) String keyword,
                           @RequestParam(defaultValue = "1") int page,
                           @RequestParam(defaultValue = "20") int size) {
        long offset = Paging.offset(page, size);
        String filterKeyword = trimmed(keyword) == null ? null : contains(keyword);
        long total = tags.count(filterKeyword);
        var items = tags.list(filterKeyword, size, offset).stream().map(Rows::view).toList();
        return new PageResult(items, total);
    }

    @PostMapping("/api/tags")
    @PreAuthorize("@ss.hasPermission('enterprise:tag:write')")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody TagInput input,
                                      @AuthenticationPrincipal AccountPrincipal actor) {
        String name = trimmed(input.tagName());
        String type = trimmed(input.tagType());
        if (name == null || type == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写标签名称和类型");
        }
        var tag = Rows.params("name", name, "type", type);
        tags.insert(tag);
        long id = ((Number) tag.get("id")).longValue();
        audits.record(actor.id(), "CREATE", "TAG", id);
        return Rows.view(Rows.required(tags.findById(id), "标签不存在"));
    }

    @GetMapping("/api/enterprises/{id}/tags")
    @PreAuthorize("@ss.hasPermission('enterprise:view')")
    public List<Map<String, Object>> assigned(@PathVariable long id) {
        if (tags.existsEnterprise(id) == null) {
            throw new ResponseStatusException(NOT_FOUND, "企业不存在");
        }
        return tags.assigned(id).stream().map(Rows::view).toList();
    }

    @PutMapping("/api/enterprises/{id}/tags")
    @PreAuthorize("@ss.hasPermission('enterprise:tag:write')")
    @Transactional
    public List<Map<String, Object>> replace(@PathVariable long id, @Valid @RequestBody TagsInput input,
                                             @AuthenticationPrincipal AccountPrincipal actor) {
        if (tags.lockEnterprise(id) == null) {
            throw new ResponseStatusException(NOT_FOUND, "企业不存在");
        }
        List<Long> selected = input.tagIds();
        if (new HashSet<>(selected).size() != selected.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标签不能重复");
        }
        if (!selected.isEmpty() && tags.countExisting(selected) != selected.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "所选标签不存在");
        }
        tags.clearEnterpriseTags(id);
        for (Long tag : selected) {
            tags.assignEnterpriseTag(id, tag);
        }
        audits.record(actor.id(), "UPDATE", "ENTERPRISE_TAG", id);
        return assigned(id);
    }

    record TagInput(@NotBlank @Size(max = 100) String tagName,
                    @NotBlank @Size(max = 50) String tagType) {}

    record TagsInput(@NotNull @Size(max = 100) List<@NotNull @Positive Long> tagIds) {}
}
