package com.enterprise.investment.business;

import com.enterprise.investment.mapper.AccountMapper;
import com.enterprise.investment.mapper.AuditMapper;
import com.enterprise.investment.mapper.BusinessMapper;
import com.enterprise.investment.mapper.ContractMapper;
import com.enterprise.investment.mapper.EnterpriseMapper;
import com.enterprise.investment.mapper.ProjectMapper;
import com.enterprise.investment.mapper.ReceivableMapper;
import com.enterprise.investment.security.AccountPrincipal;
import com.enterprise.investment.security.DataScopeResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

import static com.enterprise.investment.business.Rows.contains;
import static com.enterprise.investment.business.Rows.params;
import static com.enterprise.investment.business.Rows.trimmed;
import static com.enterprise.investment.business.Rows.view;

@RestController
@RequestMapping("/api/contracts")
@PreAuthorize("@ss.hasPermission('contract:view')")
public class ContractController {
    private static final String ENTITY = "CONTRACT";
    private static final Set<String> STATUSES = Set.of("DRAFT", "SIGNED", "TERMINATED");

    private final ContractMapper contracts;
    private final BusinessMapper businesses;
    private final ReceivableMapper receivables;
    private final EnterpriseMapper enterprises;
    private final ProjectMapper projects;
    private final AccountMapper accounts;
    private final AuditMapper audits;
    private final DataScopeResolver scopes;

    ContractController(ContractMapper contracts, BusinessMapper businesses, ReceivableMapper receivables,
                       EnterpriseMapper enterprises, ProjectMapper projects, AccountMapper accounts,
                       AuditMapper audits, DataScopeResolver scopes) {
        this.contracts = contracts;
        this.businesses = businesses;
        this.receivables = receivables;
        this.enterprises = enterprises;
        this.projects = projects;
        this.accounts = accounts;
        this.audits = audits;
        this.scopes = scopes;
    }

    @GetMapping
    public PageResult list(@RequestParam(required = false) String keyword,
                           @RequestParam(required = false) String status,
                           @RequestParam(required = false) Long customerId,
                           @RequestParam(required = false) Long businessId,
                           @RequestParam(required = false) Long ownerId,
                           @RequestParam(defaultValue = "1") int page,
                           @RequestParam(defaultValue = "20") int size,
                           @AuthenticationPrincipal AccountPrincipal actor) {
        if (status != null && !STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "合同状态无效");
        }
        Long scopeOwner = scopes.ownerFilter("contract", actor);
        String filterKeyword = trimmed(keyword) == null ? null : contains(keyword);
        long offset = Paging.offset(page, size);
        long total = contracts.count(scopeOwner, filterKeyword, status, customerId, businessId, ownerId);
        var items = contracts.list(scopeOwner, filterKeyword, status, customerId, businessId, ownerId, size, offset)
                .stream().map(Rows::view).toList();
        return new PageResult(items, total);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id, @AuthenticationPrincipal AccountPrincipal actor) {
        return view(required(id, scopes.ownerFilter("contract", actor), false));
    }

    @PostMapping
    @PreAuthorize("@ss.hasPermission('contract:create')")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody ContractInput input,
                                      @AuthenticationPrincipal AccountPrincipal actor) {
        return insertContract(input, actor);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@ss.hasPermission('contract:update')")
    @Transactional
    public Map<String, Object> update(@PathVariable long id, @Valid @RequestBody ContractInput input,
                                      @AuthenticationPrincipal AccountPrincipal actor) {
        Long scopeOwner = scopes.ownerFilter("contract", actor);
        Map<String, Object> old = required(id, scopeOwner, true);
        ContractInput normalized = normalize(input, old, actor);
        var values = params("id", id, "name", trimmed(normalized.name()), "customer", normalized.customerId(),
                "business", normalized.businessId(), "project", normalized.projectId(),
                "owner", normalized.ownerId(), "amount", normalized.amount(),
                "signDate", normalized.signDate(), "startDate", normalized.startDate(),
                "endDate", normalized.endDate(), "status", normalized.status(),
                "remark", trimmed(normalized.remark()));
        contracts.update(values);
        if ("SIGNED".equals(normalized.status()) && normalized.businessId() != null) {
            businesses.markWon(normalized.businessId(),
                    normalized.signDate() == null ? LocalDate.now() : normalized.signDate());
        }
        audits.record(actor.id(), "UPDATE", ENTITY, id);
        return get(id, actor);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@ss.hasPermission('contract:delete')")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, @AuthenticationPrincipal AccountPrincipal actor) {
        required(id, scopes.ownerFilter("contract", actor), true);
        if (receivables.countByContract(id) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该合同已有回款记录，不能删除");
        }
        contracts.delete(id);
        audits.record(actor.id(), "DELETE", ENTITY, id);
    }

    private Map<String, Object> insertContract(ContractInput input, AccountPrincipal actor) {
        ContractInput normalized = normalize(input, null, actor);
        var values = params("contractNo", CrmNumbers.next("HT"), "name", trimmed(normalized.name()),
                "customer", normalized.customerId(), "business", normalized.businessId(),
                "project", normalized.projectId(), "owner", normalized.ownerId(),
                "amount", normalized.amount(), "signDate", normalized.signDate(),
                "startDate", normalized.startDate(), "endDate", normalized.endDate(),
                "status", normalized.status(), "remark", trimmed(normalized.remark()), "actor", actor.id());
        contracts.insert(values);
        long id = ((Number) values.get("id")).longValue();
        if ("SIGNED".equals(normalized.status()) && normalized.businessId() != null) {
            businesses.markWon(normalized.businessId(),
                    normalized.signDate() == null ? LocalDate.now() : normalized.signDate());
        }
        audits.record(actor.id(), "CREATE", ENTITY, id);
        return get(id, actor);
    }

    private ContractInput normalize(ContractInput input, Map<String, Object> old, AccountPrincipal actor) {
        Long scopeOwner = scopes.ownerFilter("contract", actor);
        Long owner = input.ownerId();
        if (scopeOwner != null) {
            if (owner != null && !owner.equals(scopeOwner)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "招商人员仅可维护本人合同");
            }
            owner = scopeOwner;
        } else if (owner != null && accounts.findAssignable(owner) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "负责人必须是已启用的招商人员");
        }
        if (owner == null && old != null && old.get("owner_user_id") != null) {
            owner = ((Number) old.get("owner_user_id")).longValue();
        }
        if (owner == null) {
            owner = actor.id();
        }

        Long customer = input.customerId();
        Long business = input.businessId();
        Long project = input.projectId();
        if (customer == null && old != null && old.get("customer_id") != null) {
            customer = ((Number) old.get("customer_id")).longValue();
        }
        if (business == null && old != null && old.get("business_id") != null) {
            business = ((Number) old.get("business_id")).longValue();
        }
        if (project == null && old != null && old.get("project_id") != null) {
            project = ((Number) old.get("project_id")).longValue();
        }
        if (business != null) {
            Map<String, Object> source = businesses.findById(business, scopes.ownerFilter("business", actor));
            if (source == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "商机不存在");
            }
            long businessCustomer = ((Number) source.get("customer_id")).longValue();
            if (customer == null) {
                customer = businessCustomer;
            } else if (!customer.equals(businessCustomer)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "合同客户与商机客户不一致");
            }
            if (project == null && source.get("project_id") != null) {
                project = ((Number) source.get("project_id")).longValue();
            }
        }
        if (customer == null || enterprises.exists(customer) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "客户不存在");
        }
        if (project != null && projects.findById(project) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "招商项目不存在");
        }
        String status = trimmed(input.status());
        if (status == null) {
            status = old == null ? "DRAFT" : String.valueOf(old.get("status"));
        }
        if (!STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "合同状态无效");
        }
        LocalDate signDate = input.signDate();
        if ("SIGNED".equals(status) && signDate == null) {
            signDate = LocalDate.now();
        }
        if (input.startDate() != null && input.endDate() != null && input.endDate().isBefore(input.startDate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "合同结束日期不能早于开始日期");
        }
        return new ContractInput(input.name(), customer, business, project, owner, input.amount(),
                signDate, input.startDate(), input.endDate(), status, input.remark());
    }

    private Map<String, Object> required(long id, Long scopeOwner, boolean lock) {
        return Rows.required(lock ? contracts.lockById(id, scopeOwner) : contracts.findById(id, scopeOwner),
                "合同不存在");
    }

    record ContractInput(
            @NotBlank @Size(max = 255) String name,
            @Positive Long customerId,
            @Positive Long businessId,
            @Positive Long projectId,
            @Positive Long ownerId,
            @NotNull @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal amount,
            LocalDate signDate,
            LocalDate startDate,
            LocalDate endDate,
            @Size(max = 20) String status,
            @Size(max = 5000) String remark) {
    }
}
