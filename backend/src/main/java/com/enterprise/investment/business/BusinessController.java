package com.enterprise.investment.business;

import com.enterprise.investment.mapper.AccountMapper;
import com.enterprise.investment.mapper.AuditMapper;
import com.enterprise.investment.mapper.BusinessMapper;
import com.enterprise.investment.mapper.EnterpriseMapper;
import com.enterprise.investment.mapper.LeadMapper;
import com.enterprise.investment.mapper.ProjectMapper;
import com.enterprise.investment.security.AccountPrincipal;
import com.enterprise.investment.security.DataScopeResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.enterprise.investment.business.Rows.contains;
import static com.enterprise.investment.business.Rows.params;
import static com.enterprise.investment.business.Rows.trimmed;
import static com.enterprise.investment.business.Rows.view;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api/businesses")
@PreAuthorize("@ss.hasPermission('business:view')")
public class BusinessController {
    private static final String ENTITY = "BUSINESS";
    private static final Set<String> STAGES = Set.of(
            "NEW", "QUALIFIED", "CONTACTED", "NEGOTIATING", "WON", "LOST");

    private final BusinessMapper businesses;
    private final LeadMapper leads;
    private final EnterpriseMapper enterprises;
    private final ProjectMapper projects;
    private final AccountMapper accounts;
    private final AuditMapper audits;
    private final DataScopeResolver scopes;

    BusinessController(BusinessMapper businesses, LeadMapper leads, EnterpriseMapper enterprises,
                       ProjectMapper projects, AccountMapper accounts, AuditMapper audits,
                       DataScopeResolver scopes) {
        this.businesses = businesses;
        this.leads = leads;
        this.enterprises = enterprises;
        this.projects = projects;
        this.accounts = accounts;
        this.audits = audits;
        this.scopes = scopes;
    }

    @GetMapping
    public PageResult list(@RequestParam(required = false) String keyword,
                           @RequestParam(required = false) String stage,
                           @RequestParam(required = false) Long customerId,
                           @RequestParam(required = false) Long projectId,
                           @RequestParam(required = false) Long ownerId,
                           @RequestParam(defaultValue = "1") int page,
                           @RequestParam(defaultValue = "20") int size,
                           @AuthenticationPrincipal AccountPrincipal actor) {
        if (stage != null && !STAGES.contains(stage)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "商机阶段无效");
        }
        Long scopeOwner = scopes.ownerFilter("business", actor);
        String filterKeyword = trimmed(keyword) == null ? null : contains(keyword);
        long offset = Paging.offset(page, size);
        long total = businesses.count(scopeOwner, filterKeyword, stage, customerId, projectId, ownerId);
        var items = businesses.list(scopeOwner, filterKeyword, stage, customerId, projectId, ownerId, size, offset)
                .stream().map(Rows::view).toList();
        return new PageResult(items, total);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id, @AuthenticationPrincipal AccountPrincipal actor) {
        return view(required(id, scopes.ownerFilter("business", actor), false));
    }

    @PostMapping
    @PreAuthorize("@ss.hasPermission('business:create')")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody BusinessInput input,
                                      @AuthenticationPrincipal AccountPrincipal actor) {
        return insertBusiness(input, actor);
    }

    @PostMapping("/from-lead/{leadId}")
    @PreAuthorize("@ss.hasPermission('business:create')")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> fromLead(@PathVariable long leadId,
                                        @Valid @RequestBody FromLeadInput input,
                                        @AuthenticationPrincipal AccountPrincipal actor) {
        Map<String, Object> lead = Rows.required(
                leads.findById(leadId, scopes.leadOwner(actor)), "招商线索不存在");
        if (businesses.findByLeadIdForUpdate(leadId) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该线索已转为商机");
        }
        Long scopeOwner = scopes.ownerFilter("business", actor);
        Long owner = lead.get("owner_id") == null ? actor.id() : number(lead, "owner_id");
        if (scopeOwner != null) {
            owner = scopeOwner;
        }
        String enterpriseName = String.valueOf(lead.get("enterprise_name"));
        String projectName = lead.get("project_name") == null ? "招商商机" : String.valueOf(lead.get("project_name"));
        String name = enterpriseName + " · " + projectName;
        BusinessInput inputAsBusiness = new BusinessInput(
                name.length() > 255 ? name.substring(0, 255) : name,
                number(lead, "enterprise_id"),
                lead.get("project_id") == null ? null : number(lead, "project_id"),
                leadId, owner, "QUALIFIED", input.amount(), input.probability(),
                input.expectedCloseDate(), null, "LEAD", input.remark());
        return insertBusiness(inputAsBusiness, actor);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@ss.hasPermission('business:update')")
    @Transactional
    public Map<String, Object> update(@PathVariable long id, @Valid @RequestBody BusinessInput input,
                                      @AuthenticationPrincipal AccountPrincipal actor) {
        Long scopeOwner = scopes.ownerFilter("business", actor);
        Map<String, Object> old = required(id, scopeOwner, true);
        Long owner = normalizeOwner(input.ownerId(), scopeOwner);
        Long customer = input.customerId() != null ? input.customerId()
                : old.get("customer_id") == null ? null : number(old, "customer_id");
        Long project = input.projectId() != null ? input.projectId()
                : old.get("project_id") == null ? null : number(old, "project_id");
        Long lead = input.leadId() != null ? input.leadId()
                : old.get("lead_id") == null ? null : number(old, "lead_id");
        validateRelations(customer, project);
        String stage = normalizedStage(input.stage());
        LocalDate dealDate = stage.equals("WON")
                ? (input.dealDate() == null ? LocalDate.now() : input.dealDate())
                : input.dealDate();
        var values = params("id", id, "name", trimmed(input.name()), "customer", customer,
                "project", project, "lead", lead, "owner", owner, "stage", stage,
                "amount", input.amount(), "probability", input.probability(),
                "expectedCloseDate", input.expectedCloseDate(), "dealDate", dealDate,
                "source", trimmed(input.source()), "remark", trimmed(input.remark()));
        try {
            businesses.update(values);
        } catch (DuplicateKeyException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该线索已关联其他商机");
        }
        audits.record(actor.id(), "UPDATE", ENTITY, id);
        return get(id, actor);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@ss.hasPermission('business:delete')")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, @AuthenticationPrincipal AccountPrincipal actor) {
        required(id, scopes.ownerFilter("business", actor), true);
        businesses.delete(id);
        audits.record(actor.id(), "DELETE", ENTITY, id);
    }

    private Map<String, Object> insertBusiness(BusinessInput input, AccountPrincipal actor) {
        Long scopeOwner = scopes.ownerFilter("business", actor);
        Long owner = normalizeOwner(input.ownerId(), scopeOwner);
        Long customer = input.customerId();
        Long project = input.projectId();
        Long lead = input.leadId();
        if (lead != null) {
            Map<String, Object> source = Rows.required(leads.findById(lead, scopes.leadOwner(actor)),
                    "招商线索不存在");
            if (businesses.findByLeadIdForUpdate(lead) != null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "该线索已转为商机");
            }
            customer = number(source, "enterprise_id");
            project = source.get("project_id") == null ? null : number(source, "project_id");
        }
        validateRelations(customer, project);
        String stage = normalizedStage(input.stage());
        LocalDate dealDate = stage.equals("WON")
                ? (input.dealDate() == null ? LocalDate.now() : input.dealDate())
                : input.dealDate();
        var values = params("businessNo", CrmNumbers.next("BUS"), "name", trimmed(input.name()),
                "customer", customer, "project", project, "lead", lead, "owner", owner,
                "stage", stage, "amount", input.amount(), "probability", input.probability(),
                "expectedCloseDate", input.expectedCloseDate(), "dealDate", dealDate,
                "source", trimmed(input.source()), "remark", trimmed(input.remark()), "actor", actor.id());
        try {
            businesses.insert(values);
        } catch (DuplicateKeyException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该线索已关联其他商机");
        }
        long id = number(values, "id");
        audits.record(actor.id(), "CREATE", ENTITY, id);
        return get(id, actor);
    }

    private Long normalizeOwner(Long requested, Long scopeOwner) {
        if (scopeOwner != null) {
            if (requested != null && !requested.equals(scopeOwner)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "招商人员仅可将商机分配给本人");
            }
            return scopeOwner;
        }
        if (requested != null && accounts.findAssignable(requested) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "负责人必须是已启用的招商人员");
        }
        return requested;
    }

    private void validateRelations(Long customer, Long project) {
        if (customer == null || enterprises.exists(customer) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "客户不存在");
        }
        if (project != null && projects.findById(project) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "招商项目不存在");
        }
    }

    private static String normalizedStage(String stage) {
        String value = trimmed(stage);
        if (value == null) {
            return "NEW";
        }
        if (!STAGES.contains(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "商机阶段无效");
        }
        return value;
    }

    private Map<String, Object> required(long id, Long scopeOwner, boolean lock) {
        return Rows.required(lock ? businesses.lockById(id, scopeOwner) : businesses.findById(id, scopeOwner),
                "商机不存在");
    }

    private static long number(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new IllegalStateException("缺少字段 " + key);
    }

    record BusinessInput(
            @NotBlank @Size(max = 255) String name,
            @Positive Long customerId,
            @Positive Long projectId,
            @Positive Long leadId,
            @Positive Long ownerId,
            @Size(max = 30) String stage,
            @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal amount,
            @Min(0) @Max(100) Integer probability,
            LocalDate expectedCloseDate,
            LocalDate dealDate,
            @Size(max = 50) String source,
            @Size(max = 5000) String remark) {
    }

    record FromLeadInput(
            @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal amount,
            @Min(0) @Max(100) Integer probability,
            LocalDate expectedCloseDate,
            @Size(max = 5000) String remark) {
    }
}
