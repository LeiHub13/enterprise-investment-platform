package com.enterprise.investment.business;

import com.enterprise.investment.mapper.AccountMapper;
import com.enterprise.investment.mapper.AuditMapper;
import com.enterprise.investment.mapper.ContractMapper;
import com.enterprise.investment.mapper.ReceivableMapper;
import com.enterprise.investment.security.AccountPrincipal;
import com.enterprise.investment.security.DataScopeResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
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
@RequestMapping("/api/receivables")
@PreAuthorize("@ss.hasPermission('receivable:view')")
public class ReceivableController {
    private static final String ENTITY = "RECEIVABLE";
    private static final Set<String> STATUSES = Set.of("PENDING", "RECEIVED");

    private final ReceivableMapper receivables;
    private final ContractMapper contracts;
    private final AccountMapper accounts;
    private final AuditMapper audits;
    private final DataScopeResolver scopes;

    ReceivableController(ReceivableMapper receivables, ContractMapper contracts, AccountMapper accounts,
                         AuditMapper audits, DataScopeResolver scopes) {
        this.receivables = receivables;
        this.contracts = contracts;
        this.accounts = accounts;
        this.audits = audits;
        this.scopes = scopes;
    }

    @GetMapping
    public PageResult list(@RequestParam(required = false) String keyword,
                           @RequestParam(required = false) String status,
                           @RequestParam(required = false) Long contractId,
                           @RequestParam(required = false) Long customerId,
                           @RequestParam(required = false) Long ownerId,
                           @RequestParam(defaultValue = "1") int page,
                           @RequestParam(defaultValue = "20") int size,
                           @AuthenticationPrincipal AccountPrincipal actor) {
        if (status != null && !STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "回款状态无效");
        }
        Long scopeOwner = scopes.ownerFilter("receivable", actor);
        String filterKeyword = trimmed(keyword) == null ? null : contains(keyword);
        long offset = Paging.offset(page, size);
        long total = receivables.count(scopeOwner, filterKeyword, status, contractId, customerId, ownerId);
        var items = receivables.list(scopeOwner, filterKeyword, status, contractId, customerId, ownerId, size, offset)
                .stream().map(Rows::view).toList();
        return new PageResult(items, total);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id, @AuthenticationPrincipal AccountPrincipal actor) {
        return view(required(id, scopes.ownerFilter("receivable", actor), false));
    }

    @PostMapping
    @PreAuthorize("@ss.hasPermission('receivable:create')")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody ReceivableInput input,
                                      @AuthenticationPrincipal AccountPrincipal actor) {
        return insertReceivable(input, actor);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@ss.hasPermission('receivable:update')")
    @Transactional
    public Map<String, Object> update(@PathVariable long id, @Valid @RequestBody ReceivableInput input,
                                      @AuthenticationPrincipal AccountPrincipal actor) {
        Long scopeOwner = scopes.ownerFilter("receivable", actor);
        Map<String, Object> old = required(id, scopeOwner, true);
        ReceivableInput normalized = normalize(input, old, actor);
        var values = params("id", id, "contract", normalized.contractId(),
                "customer", normalized.customerId(), "business", normalized.businessId(),
                "owner", normalized.ownerId(), "planAmount", normalized.planAmount(),
                "planDate", normalized.planDate(), "receivedAmount", normalized.receivedAmount(),
                "receivedDate", normalized.receivedDate(), "status", normalized.status(),
                "remark", trimmed(normalized.remark()));
        receivables.update(values);
        audits.record(actor.id(), "UPDATE", ENTITY, id);
        return get(id, actor);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@ss.hasPermission('receivable:delete')")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, @AuthenticationPrincipal AccountPrincipal actor) {
        required(id, scopes.ownerFilter("receivable", actor), true);
        receivables.delete(id);
        audits.record(actor.id(), "DELETE", ENTITY, id);
    }

    private Map<String, Object> insertReceivable(ReceivableInput input, AccountPrincipal actor) {
        ReceivableInput normalized = normalize(input, null, actor);
        var values = params("receivableNo", CrmNumbers.next("HK"), "contract", normalized.contractId(),
                "customer", normalized.customerId(), "business", normalized.businessId(),
                "owner", normalized.ownerId(), "planAmount", normalized.planAmount(),
                "planDate", normalized.planDate(), "receivedAmount", normalized.receivedAmount(),
                "receivedDate", normalized.receivedDate(), "status", normalized.status(),
                "remark", trimmed(normalized.remark()), "actor", actor.id());
        receivables.insert(values);
        long id = ((Number) values.get("id")).longValue();
        audits.record(actor.id(), "CREATE", ENTITY, id);
        return get(id, actor);
    }

    private ReceivableInput normalize(ReceivableInput input, Map<String, Object> old, AccountPrincipal actor) {
        Long contract = input.contractId();
        if (contract == null && old != null && old.get("contract_id") != null) {
            contract = ((Number) old.get("contract_id")).longValue();
        }
        if (contract == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "合同不能为空");
        }
        Map<String, Object> contractRow = contracts.findById(contract,
                scopes.ownerFilter("contract", actor));
        if (contractRow == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "合同不存在");
        }
        Long customer = ((Number) contractRow.get("customer_id")).longValue();
        Long business = contractRow.get("business_id") == null
                ? null : ((Number) contractRow.get("business_id")).longValue();
        Long owner = input.ownerId();
        Long receivableScope = scopes.ownerFilter("receivable", actor);
        if (receivableScope != null) {
            if (owner != null && !owner.equals(receivableScope)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "招商人员仅可维护本人回款");
            }
            owner = receivableScope;
        } else if (owner != null && accounts.findAssignable(owner) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "负责人必须是已启用的招商人员");
        }
        if (owner == null && contractRow.get("owner_user_id") != null) {
            owner = ((Number) contractRow.get("owner_user_id")).longValue();
        }
        if (owner == null) {
            owner = actor.id();
        }
        String status = trimmed(input.status());
        if (status == null) {
            status = old == null ? "PENDING" : String.valueOf(old.get("status"));
        }
        if (!STATUSES.contains(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "回款状态无效");
        }
        BigDecimal received = input.receivedAmount() == null ? BigDecimal.ZERO : input.receivedAmount();
        if ("RECEIVED".equals(status) && input.receivedAmount() == null) {
            received = input.planAmount();
        }
        if (received.compareTo(input.planAmount()) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "实收金额不能大于计划金额");
        }
        LocalDate receivedDate = input.receivedDate();
        if ("RECEIVED".equals(status) && receivedDate == null) {
            receivedDate = LocalDate.now();
        }
        return new ReceivableInput(contract, owner, customer, business, input.planAmount(),
                input.planDate(), received, receivedDate, status, input.remark());
    }

    private Map<String, Object> required(long id, Long scopeOwner, boolean lock) {
        return Rows.required(lock ? receivables.lockById(id, scopeOwner) : receivables.findById(id, scopeOwner),
                "回款记录不存在");
    }

    record ReceivableInput(
            @Positive Long contractId,
            @Positive Long ownerId,
            Long customerId,
            Long businessId,
            @NotNull @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal planAmount,
            LocalDate planDate,
            @DecimalMin("0") @Digits(integer = 16, fraction = 2) BigDecimal receivedAmount,
            LocalDate receivedDate,
            @Size(max = 20) String status,
            @Size(max = 5000) String remark) {
    }
}
