package com.enterprise.investment.business;

import com.enterprise.investment.mapper.AccountMapper;
import com.enterprise.investment.mapper.AuditMapper;
import com.enterprise.investment.mapper.ProjectMapper;
import com.enterprise.investment.security.AccountPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;

import static com.enterprise.investment.business.Rows.contains;
import static com.enterprise.investment.business.Rows.params;
import static com.enterprise.investment.business.Rows.trimmed;
import static com.enterprise.investment.business.Rows.view;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping({"/api/projects", "/api/buildings"})
@PreAuthorize("@ss.hasPermission('project:view')")
public class ProjectController {
    private static final String WRITE = "@ss.hasPermission('project:write')";
    private static final String ENTITY = "PROJECT";
    private static final Set<String> STATUSES = Set.of("DRAFT", "ACTIVE", "PAUSED", "CLOSED");
    private final ProjectMapper projects;
    private final AccountMapper accounts;
    private final AuditMapper audits;

    ProjectController(ProjectMapper projects, AccountMapper accounts, AuditMapper audits) {
        this.projects = projects;
        this.accounts = accounts;
        this.audits = audits;
    }

    @GetMapping
    public PageResult list(@RequestParam(required = false) String keyword,
                           @RequestParam(required = false) String projectStatus,
                           @RequestParam(defaultValue = "1") int page,
                           @RequestParam(defaultValue = "20") int size) {
        long offset = Paging.offset(page, size);
        String filterKeyword = trimmed(keyword) == null ? null : contains(keyword);
        String status = trimmed(projectStatus);
        long total = projects.count(filterKeyword, status);
        var items = projects.list(filterKeyword, status, size, offset).stream().map(Rows::view).toList();
        return new PageResult(items, total);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        return view(Rows.required(projects.findById(id), "招商项目不存在"));
    }

    @PostMapping
    @PreAuthorize(WRITE)
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody ProjectInput input,
                                      @AuthenticationPrincipal AccountPrincipal actor) {
        var values = values(input);
        values.put("actor", actor.id());
        projects.insert(values);
        long id = ((Number) values.get("id")).longValue();
        audits.record(actor.id(), "CREATE", ENTITY, id);
        return get(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize(WRITE)
    @Transactional
    public Map<String, Object> replace(@PathVariable long id, @Valid @RequestBody ProjectInput input,
                                       @AuthenticationPrincipal AccountPrincipal actor) {
        lock(id);
        var values = values(input);
        values.put("id", id);
        projects.update(values);
        audits.record(actor.id(), "UPDATE", ENTITY, id);
        return get(id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(WRITE)
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, @AuthenticationPrincipal AccountPrincipal actor) {
        lock(id);
        projects.delete(id);
        audits.record(actor.id(), "DELETE", ENTITY, id);
    }

    private void lock(long id) {
        if (projects.lock(id) == null) {
            throw new ResponseStatusException(NOT_FOUND, "招商项目不存在");
        }
    }

    private Map<String, Object> values(ProjectInput input) {
        String status = trimmed(input.projectStatus());
        if (status != null && !STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的招商项目状态");
        }
        if (input.investmentMin() != null && input.investmentMax() != null
                && input.investmentMin().compareTo(input.investmentMax()) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "最低投资规模不能超过最高投资规模");
        }
        if (input.ownerId() != null && accounts.findAssignable(input.ownerId()) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "负责人必须是已启用的管理员或招商人员");
        }
        return params("projectName", trimmed(input.projectName()), "province", trimmed(input.provinceCode()),
                "city", trimmed(input.cityCode()), "industry", trimmed(input.industryCode()),
                "min", input.investmentMin(), "max", input.investmentMax(),
                "requirements", trimmed(input.requirements()), "status", status == null ? "ACTIVE" : status,
                "owner", input.ownerId());
    }

    record ProjectInput(
            @NotBlank @Size(max = 255) String projectName,
            @Size(max = 12) String provinceCode,
            @Size(max = 12) String cityCode,
            @Size(max = 20) String industryCode,
            @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal investmentMin,
            @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal investmentMax,
            @Size(max = 15000) String requirements,
            @Size(max = 30) String projectStatus,
            @Positive Long ownerId
    ) {}
}
