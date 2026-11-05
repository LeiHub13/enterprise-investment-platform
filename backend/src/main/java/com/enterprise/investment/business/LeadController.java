package com.enterprise.investment.business;

import com.enterprise.investment.security.AccountPrincipal;
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

import static com.enterprise.investment.business.BusinessData.*;

@RestController
@RequestMapping("/api/leads")
@PreAuthorize("hasAnyRole('ADMIN','INVESTMENT_MANAGER','INVESTMENT_OFFICER')")
public class LeadController {
    private static final String ENTITY = "LEAD";
    private static final List<String> STAGES = List.of("NEW", "QUALIFIED", "CONTACTED", "NEGOTIATING", "WON");
    private static final String FIELDS = """
            l.id, l.enterprise_id, e.name AS enterprise_name, l.project_id, p.project_name, l.source_channel,
            l.lead_status, l.lead_score, l.owner_id, u.username AS owner_username, l.next_followup_at,
            l.created_at, l.updated_at
            """;
    private static final String FROM = """
            FROM crm_lead l
            JOIN ent_enterprise e ON l.enterprise_id = e.id
            LEFT JOIN crm_project p ON l.project_id = p.id
            LEFT JOIN sys_user u ON l.owner_id = u.id
            """;
    private static final String FOLLOWUP_FIELDS = """
            f.id, f.lead_id, f.contact_id, c.name AS contact_name,
            f.followup_type, f.content, f.result, f.next_action, f.next_followup_at,
            f.created_by, u.username AS created_by_username, f.created_at
            """;
    private static final String FOLLOWUP_FROM = """
            FROM crm_followup f
            JOIN crm_lead l ON f.lead_id = l.id
            LEFT JOIN crm_contact c ON f.contact_id = c.id
            LEFT JOIN sys_user u ON f.created_by = u.id
            """;
    private final BusinessData db;

    LeadController(BusinessData db) {
        this.db = db;
    }

    @GetMapping
    public BusinessData.Page list(@RequestParam(required = false) String keyword,
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
        return db.page(FIELDS, FROM, """
                 WHERE (:scopeOwner IS NULL OR l.owner_id = :scopeOwner)
                   AND (:keyword IS NULL OR e.name LIKE :keyword ESCAPE '!' OR p.project_name LIKE :keyword ESCAPE '!')
                   AND (:status IS NULL OR l.lead_status = :status)
                   AND (:enterprise IS NULL OR l.enterprise_id = :enterprise)
                   AND (:project IS NULL OR l.project_id = :project)
                   AND (:owner IS NULL OR l.owner_id = :owner)
                   AND (:due IS NULL OR (
                     l.lead_status NOT IN ('WON', 'LOST') AND l.next_followup_at IS NOT NULL
                     AND (
                       (:due = 'overdue' AND l.next_followup_at < NOW())
                       OR (:due = 'today' AND l.next_followup_at >= NOW()
                           AND l.next_followup_at < CURRENT_DATE + INTERVAL 1 DAY)
                       OR (:due = 'upcoming' AND l.next_followup_at >= NOW()
                           AND l.next_followup_at < CURRENT_DATE + INTERVAL 7 DAY)
                     )
                   ))
                """, "l.id DESC", params("scopeOwner", ownerScope(actor),
                "keyword", trimmed(keyword) == null ? null : contains(keyword), "status", trimmed(leadStatus),
                "enterprise", enterpriseId, "project", projectId, "owner", ownerId, "due", due), page, size);
    }

    @GetMapping("/{id}")
    public Map<String, Object> get(@PathVariable long id, @AuthenticationPrincipal AccountPrincipal actor) {
        return view(db.one("SELECT " + FIELDS + FROM + """
                WHERE l.id = :id AND (:scopeOwner IS NULL OR l.owner_id = :scopeOwner)
                """, scope(id, actor), "招商线索不存在"));
    }

    @PostMapping
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@Valid @RequestBody LeadInput input,
                                      @AuthenticationPrincipal AccountPrincipal actor) {
        String requested = trimmed(input.leadStatus());
        if (requested != null && !requested.equals("NEW")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新建线索的状态须为 NEW");
        }
        var values = values(input, "NEW", actor);
        values.put("actor", actor.id());
        long id = db.insert("""
                INSERT INTO crm_lead
                  (enterprise_id, project_id, source_channel, lead_status, lead_score, owner_id,
                   next_followup_at, created_by)
                VALUES (:enterprise, :project, :source, :status, :score, :owner, :nextFollowup, :actor)
                """, values);
        db.audit(actor.id(), "CREATE", ENTITY, id);
        return get(id, actor);
    }

    @PutMapping("/{id}")
    @Transactional
    public Map<String, Object> replace(@PathVariable long id, @Valid @RequestBody LeadInput input,
                                       @AuthenticationPrincipal AccountPrincipal actor) {
        var old = lock(id, actor);
        String current = (String) old.get("lead_status");
        String next = trimmed(input.leadStatus());
        if (next == null) {
            next = current;
        }
        checkTransition(current, next);
        if (!Objects.equals(((Number) old.get("enterprise_id")).longValue(), input.enterpriseId())
                && !db.query("SELECT id FROM crm_followup WHERE lead_id = :id LIMIT 1", params("id", id)).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "已有跟进记录的线索不能更换关联企业");
        }
        var values = values(input, next, actor);
        values.put("id", id);
        db.update("""
                UPDATE crm_lead SET enterprise_id = :enterprise, project_id = :project, source_channel = :source,
                  lead_status = :status, lead_score = :score, owner_id = :owner, next_followup_at = :nextFollowup
                WHERE id = :id
                """, values);
        db.audit(actor.id(), "UPDATE", ENTITY, id);
        return get(id, actor);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','INVESTMENT_MANAGER')")
    @Transactional
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, @AuthenticationPrincipal AccountPrincipal actor) {
        lock(id, actor);
        db.update("DELETE FROM crm_lead WHERE id = :id", params("id", id));
        db.audit(actor.id(), "DELETE", ENTITY, id);
    }

    @GetMapping("/{id}/followups")
    public BusinessData.Page followups(@PathVariable long id,
                                      @RequestParam(defaultValue = "1") int page,
                                      @RequestParam(defaultValue = "20") int size,
                                      @AuthenticationPrincipal AccountPrincipal actor) {
        var args = scope(id, actor);
        db.one("""
                SELECT id FROM crm_lead WHERE id = :id AND (:scopeOwner IS NULL OR owner_id = :scopeOwner)
                """, args, "招商线索不存在");
        return db.page(FOLLOWUP_FIELDS, FOLLOWUP_FROM, """
                 WHERE f.lead_id = :id AND (:scopeOwner IS NULL OR l.owner_id = :scopeOwner)
                """, "f.id DESC", args, page, size);
    }

    @PostMapping("/{id}/followups")
    @Transactional
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> addFollowup(@PathVariable long id, @Valid @RequestBody FollowupInput input,
                                           @AuthenticationPrincipal AccountPrincipal actor) {
        var lead = lock(id, actor);
        if (input.contactId() != null) {
            db.one("""
                    SELECT id FROM crm_contact
                    WHERE id = :contact AND enterprise_id = :enterprise FOR SHARE
                    """, params("contact", input.contactId(), "enterprise", lead.get("enterprise_id")),
                    "联系人不存在或不属于该企业");
        }
        long followup = db.insert("""
                INSERT INTO crm_followup
                  (lead_id, contact_id, followup_type, content, result, next_action, next_followup_at, created_by)
                VALUES (:lead, :contact, :type, :content, :result, :action, :nextFollowup, :actor)
                """, params("lead", id, "contact", input.contactId(),
                "type", trimmed(input.followupType()), "content", trimmed(input.content()),
                "result", trimmed(input.result()), "action", trimmed(input.nextAction()),
                "nextFollowup", input.nextFollowupAt(), "actor", actor.id()));
        if (input.nextFollowupAt() != null || input.clearNextFollowup()) {
            db.update("UPDATE crm_lead SET next_followup_at = :nextFollowup WHERE id = :id",
                    params("nextFollowup", input.nextFollowupAt(), "id", id));
        }
        db.audit(actor.id(), "CREATE", "FOLLOWUP", followup);
        return view(db.one("SELECT " + FOLLOWUP_FIELDS + FOLLOWUP_FROM + " WHERE f.id = :id",
                params("id", followup), "跟进记录不存在"));
    }

    private Map<String, Object> lock(long id, AccountPrincipal actor) {
        return db.one("""
                SELECT id, enterprise_id, lead_status FROM crm_lead
                WHERE id = :id AND (:scopeOwner IS NULL OR owner_id = :scopeOwner)
                FOR UPDATE
                """, scope(id, actor), "招商线索不存在");
    }

    private Map<String, Object> values(LeadInput input, String status, AccountPrincipal actor) {
        Long owner = input.ownerId();
        Long scope = ownerScope(actor);
        if (scope != null) {
            if (owner != null && !owner.equals(scope)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "招商人员仅可将线索分配给本人");
            }
            owner = scope;
        }
        db.checkAssignable(owner);
        return params("enterprise", input.enterpriseId(), "project", input.projectId(),
                "source", trimmed(input.sourceChannel()), "status", status, "score", input.leadScore(),
                "owner", owner, "nextFollowup", input.nextFollowupAt());
    }

    private static Map<String, Object> scope(long id, AccountPrincipal actor) {
        return params("id", id, "scopeOwner", ownerScope(actor));
    }

    private static Long ownerScope(AccountPrincipal actor) {
        return "INVESTMENT_OFFICER".equals(actor.role()) ? actor.id() : null;
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
