package com.enterprise.investment.business;

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

import static com.enterprise.investment.business.BusinessData.*;

@RestController
@RequestMapping("/api/projects")
@PreAuthorize("hasAnyRole('ADMIN','INVESTMENT_MANAGER','INVESTMENT_OFFICER')")
public class ProjectController {
    private static final String WRITE = "hasAnyRole('ADMIN','INVESTMENT_MANAGER')";
    private static final String ENTITY = "PROJECT";
    private static final Set<String> STATUSES = Set.of("DRAFT", "ACTIVE", "PAUSED", "CLOSED");
    private static final String FIELDS = """
            p.id, p.project_name, p.province_code, p.city_code, p.industry_code, p.investment_min,
            p.investment_max, p.requirements, p.project_status, p.owner_id, u.username AS owner_username,
            p.created_at, p.updated_at
            """;
    private static final String FROM = "FROM crm_project p LEFT JOIN sys_user u ON p.owner_id = u.id";
    private final BusinessData db;

    ProjectController(BusinessData db) {
        this.db = db;
    }

    @GetMapping
    public BusinessData.Page list(@RequestParam(required = false) String keyword,
                                  @RequestParam(required = false) String projectStatus,
                                  @RequestParam(defaultValue = "1") int page,
                                  @RequestParam(defaultValue = "20") int size) {
        return db.page(FIELDS, FROM, """
                 WHERE (:keyword IS NULL OR p.project_name LIKE :keyword ESCAPE '!')
                   AND (:status IS NULL OR p.project_status = :status)
                """, "p.id DESC",
                params("keyword", trimmed(keyword) == null ? null : contains(keyword), "status", trimmed(projectStatus)),
                page, size);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id) {
        return view(db.one("SELECT " + FIELDS + FROM + " WHERE p.id = :id", params("id", id), "招商项目不存在"));
    }

    @PostMapping
    @PreAuthorize(WRITE)
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody ProjectInput input,
                                      @AuthenticationPrincipal AccountPrincipal actor) {
        var values = values(input);
        values.put("actor", actor.id());
        long id = db.insert("""
                INSERT INTO crm_project
                  (project_name, province_code, city_code, industry_code, investment_min, investment_max,
                   requirements, project_status, owner_id, created_by)
                VALUES (:projectName, :province, :city, :industry, :min, :max, :requirements, :status, :owner, :actor)
                """, values);
        db.audit(actor.id(), "CREATE", ENTITY, id);
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
        db.update("""
                UPDATE crm_project SET project_name = :projectName, province_code = :province, city_code = :city,
                  industry_code = :industry, investment_min = :min, investment_max = :max, requirements = :requirements,
                  project_status = :status, owner_id = :owner
                WHERE id = :id
                """, values);
        db.audit(actor.id(), "UPDATE", ENTITY, id);
        return get(id);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(WRITE)
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, @AuthenticationPrincipal AccountPrincipal actor) {
        lock(id);
        db.update("DELETE FROM crm_project WHERE id = :id", params("id", id));
        db.audit(actor.id(), "DELETE", ENTITY, id);
    }

    private void lock(long id) {
        db.one("SELECT id FROM crm_project WHERE id = :id FOR UPDATE", params("id", id), "招商项目不存在");
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
        db.checkAssignable(input.ownerId());
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
