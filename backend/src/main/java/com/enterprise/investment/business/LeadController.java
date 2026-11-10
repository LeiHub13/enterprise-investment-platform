package com.enterprise.investment.business;

import com.enterprise.investment.mapper.AccountMapper;
import com.enterprise.investment.mapper.AgentLeadMapper;
import com.enterprise.investment.mapper.AuditMapper;
import com.enterprise.investment.mapper.LeadMapper;
import com.enterprise.investment.security.AccountPrincipal;
import com.enterprise.investment.security.DataScopeResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.enterprise.investment.business.Rows.contains;
import static com.enterprise.investment.business.Rows.params;
import static com.enterprise.investment.business.Rows.trimmed;
import static com.enterprise.investment.business.Rows.view;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@RestController
@RequestMapping("/api/leads")
@PreAuthorize("@ss.hasPermission('lead:view')")
public class LeadController {
    private static final String ENTITY = "LEAD";
    private static final List<String> STAGES = List.of("NEW", "QUALIFIED", "CONTACTED", "NEGOTIATING", "WON");
    private final LeadMapper leads;
    private final AccountMapper accounts;
    private final AuditMapper audits;
    private final DataScopeResolver scopes;
    private final AgentLeadMapper agentLeads;

    LeadController(LeadMapper leads, AccountMapper accounts, AuditMapper audits, DataScopeResolver scopes,
                   AgentLeadMapper agentLeads) {
        this.leads = leads;
        this.accounts = accounts;
        this.audits = audits;
        this.scopes = scopes;
        this.agentLeads = agentLeads;
    }

    @GetMapping
    public PageResult list(@RequestParam(required = false) String keyword,
                           @RequestParam(required = false) String leadStatus,
                           @RequestParam(required = false) Long enterpriseId,
                           @RequestParam(required = false) Long projectId,
                           @RequestParam(required = false) Long ownerId,
                           @RequestParam(required = false) String due,
                           @RequestParam(defaultValue = "1") int page,
                           @RequestParam(defaultValue = "20") int size,
                           @AuthenticationPrincipal AccountPrincipal actor) {
        if (due != null && !List.of("overdue", "today", "upcoming").contains(due)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "待跟进筛选条件无效");
        }
        long offset = Paging.offset(page, size);
        Long scope = scopes.leadOwner(actor);
        String status = trimmed(leadStatus);
        String filterKeyword = trimmed(keyword) == null ? null : contains(keyword);
        long total = leads.count(scope, filterKeyword, status, enterpriseId, projectId, ownerId, due);
        var items = leads.list(scope, filterKeyword, status, enterpriseId, projectId, ownerId, due, size, offset)
                .stream().map(Rows::view).toList();
        return new PageResult(items, total);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id, @AuthenticationPrincipal AccountPrincipal actor) {
        return view(Rows.required(leads.findById(id, scopes.leadOwner(actor)), "招商线索不存在"));
    }

    @PostMapping
    @PreAuthorize("@ss.hasPermission('lead:create')")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody LeadInput input,
                                      @AuthenticationPrincipal AccountPrincipal actor) {
        if (input.sourceChannel() != null && input.sourceChannel().trim().toUpperCase().startsWith("AGENT")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Agent 线索必须经过任务确认并附带证据");
        }
        String requested = trimmed(input.leadStatus());
        if (requested != null && !requested.equals("NEW")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新建线索的状态须为 NEW");
        }
        var values = values(input, "NEW", actor);
        values.put("actor", actor.id());
        leads.insert(values);
        long id = ((Number) values.get("id")).longValue();
        audits.record(actor.id(), "CREATE", ENTITY, id);
        return get(id, actor);
    }

    @PutMapping("/{id}")
    @PreAuthorize("@ss.hasPermission('lead:update')")
    @Transactional
    public Map<String, Object> replace(@PathVariable long id, @Valid @RequestBody LeadInput input,
                                       @AuthenticationPrincipal AccountPrincipal actor) {
        var old = lock(id, actor);
        String current = (String) old.get("lead_status");
        if (List.of("PENDING_REVIEW", "REJECTED").contains(current)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "待审核或已驳回线索不能直接编辑");
        }
        if ("AGENT".equals(old.get("source_channel"))
                && (!Objects.equals(((Number) old.get("enterprise_id")).longValue(), input.enterpriseId())
                    || !Objects.equals(old.get("project_id") == null ? null : ((Number) old.get("project_id")).longValue(), input.projectId())
                    || !"AGENT".equals(trimmed(input.sourceChannel())))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Agent 线索不能更换其证据对应的企业、项目或来源");
        }
        String next = trimmed(input.leadStatus());
        if (next == null) {
            next = current;
        }
        checkTransition(current, next);
        if (!Objects.equals(((Number) old.get("enterprise_id")).longValue(), input.enterpriseId())
                && leads.countFollowups(id) > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已有跟进记录的线索不能更换关联企业");
        }
        var values = values(input, next, actor);
        values.put("id", id);
        leads.update(values);
        audits.record(actor.id(), "UPDATE", ENTITY, id);
        return get(id, actor);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("@ss.hasPermission('lead:delete')")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, @AuthenticationPrincipal AccountPrincipal actor) {
        lock(id, actor);
        leads.delete(id);
        audits.record(actor.id(), "DELETE", ENTITY, id);
    }

    @GetMapping("/{id}/evidence")
    public List<Map<String, Object>> evidence(@PathVariable long id, @AuthenticationPrincipal AccountPrincipal actor) {
        Rows.required(leads.findById(id, scopes.leadOwner(actor)), "招商线索不存在");
        return agentLeads.listEvidence(id).stream().map(Rows::view).toList();
    }

    @GetMapping("/{id}/analysis")
    public Map<String, Object> analysis(@PathVariable long id, @AuthenticationPrincipal AccountPrincipal actor) {
        Rows.required(leads.findById(id, scopes.leadOwner(actor)), "招商线索不存在");
        Map<String, Object> result = agentLeads.findLeadAnalysis(id);
        if (result == null) return Map.of("available", false);
        Map<String, Object> view = Rows.view(result);
        view.put("available", true);
        return view;
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("@ss.hasPermission('lead:update')")
    @Transactional
    public Map<String, Object> approve(@PathVariable long id, @AuthenticationPrincipal AccountPrincipal actor) {
        return review(id, actor, "NEW", "APPROVE");
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("@ss.hasPermission('lead:update')")
    @Transactional
    public Map<String, Object> reject(@PathVariable long id, @AuthenticationPrincipal AccountPrincipal actor) {
        return review(id, actor, "REJECTED", "REJECT");
    }

    private Map<String, Object> review(long id, AccountPrincipal actor, String next, String action) {
        var lead = lock(id, actor);
        if (!"PENDING_REVIEW".equals(lead.get("lead_status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "只有待审核线索可以审核");
        }
        leads.updateStatus(id, next);
        audits.record(actor.id(), action, ENTITY, id);
        return get(id, actor);
    }

    @GetMapping("/{id}/followups")
    public PageResult followups(@PathVariable long id,
                                @RequestParam(defaultValue = "1") int page,
                                @RequestParam(defaultValue = "20") int size,
                                @AuthenticationPrincipal AccountPrincipal actor) {
        Long scope = scopes.leadOwner(actor);
        Rows.required(leads.findById(id, scope), "招商线索不存在");
        long offset = Paging.offset(page, size);
        long total = leads.countFollowupsPage(id, scope);
        var items = leads.listFollowups(id, scope, size, offset).stream().map(Rows::view).toList();
        return new PageResult(items, total);
    }

    @PostMapping("/{id}/followups")
    @PreAuthorize("@ss.hasPermission('lead:update')")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addFollowup(@PathVariable long id, @Valid @RequestBody FollowupInput input,
                                           @AuthenticationPrincipal AccountPrincipal actor) {
        var lead = lock(id, actor);
        if (List.of("PENDING_REVIEW", "REJECTED").contains(lead.get("lead_status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "线索需审核通过后才能跟进");
        }
        if (input.contactId() != null && leads.lockContact(input.contactId(),
                ((Number) lead.get("enterprise_id")).longValue()) == null) {
            throw new ResponseStatusException(NOT_FOUND, "联系人不存在或不属于该企业");
        }
        var values = params("lead", id, "contact", input.contactId(),
                "type", trimmed(input.followupType()), "content", trimmed(input.content()),
                "result", trimmed(input.result()), "action", trimmed(input.nextAction()),
                "nextFollowup", input.nextFollowupAt(), "actor", actor.id());
        leads.insertFollowup(values);
        long followup = ((Number) values.get("id")).longValue();
        if (input.nextFollowupAt() != null || input.clearNextFollowup()) {
            leads.updateNextFollowup(id, input.nextFollowupAt());
        }
        audits.record(actor.id(), "CREATE", "FOLLOWUP", followup);
        return view(Rows.required(leads.findFollowup(followup), "跟进记录不存在"));
    }

    private Map<String, Object> lock(long id, AccountPrincipal actor) {
        return Rows.required(leads.lockById(id, scopes.leadOwner(actor)), "招商线索不存在");
    }

    private Map<String, Object> values(LeadInput input, String status, AccountPrincipal actor) {
        Long owner = input.ownerId();
        Long scope = scopes.leadOwner(actor);
        if (scope != null) {
            if (owner != null && !owner.equals(scope)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "招商人员仅可将线索分配给本人");
            }
            owner = scope;
        }
        if (owner != null && accounts.findAssignable(owner) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "负责人必须是已启用的管理员或招商人员");
        }
        return params("enterprise", input.enterpriseId(), "project", input.projectId(),
                "source", trimmed(input.sourceChannel()), "status", status, "score", input.leadScore(),
                "owner", owner, "nextFollowup", input.nextFollowupAt());
    }

    private static void checkTransition(String current, String next) {
        if (current.equals(next)) {
            return;
        }
        int index = STAGES.indexOf(current);
        boolean active = index >= 0 && index < STAGES.size() - 1;
        if (!active || (!next.equals("LOST") && !STAGES.get(index + 1).equals(next))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "线索状态应按当前阶段顺序推进");
        }
    }

    record LeadInput(
            @NotNull @Positive Long enterpriseId,
            @Positive Long projectId,
            @Size(max = 50) String sourceChannel,
            @Size(max = 30) String leadStatus,
            @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2) BigDecimal leadScore,
            @Positive Long ownerId,
            LocalDateTime nextFollowupAt
    ) {}

    record FollowupInput(
            @Positive Long contactId,
            @Size(max = 30) String followupType,
            @NotBlank @Size(max = 15000) String content,
            @Size(max = 15000) String result,
            @Size(max = 15000) String nextAction,
            LocalDateTime nextFollowupAt,
            boolean clearNextFollowup
    ) {}
}
