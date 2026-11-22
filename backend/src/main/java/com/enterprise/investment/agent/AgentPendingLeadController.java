package com.enterprise.investment.agent;

import com.enterprise.investment.business.Rows;
import com.enterprise.investment.mapper.AgentLeadMapper;
import com.enterprise.investment.mapper.AuditMapper;
import com.enterprise.investment.mapper.EnterpriseVerificationMapper;
import com.enterprise.investment.mapper.LeadMapper;
import com.enterprise.investment.security.AccountPrincipal;
import com.enterprise.investment.security.DataScopeResolver;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/agent/leads")
@PreAuthorize("@ss.hasPermission('agent:use')")
public class AgentPendingLeadController {
    private final AgentLeadMapper agentLeads;
    private final LeadMapper leads;
    private final AuditMapper audits;
    private final DataScopeResolver scopes;
    private final EnterpriseVerificationMapper verifications;

    AgentPendingLeadController(AgentLeadMapper agentLeads, LeadMapper leads, AuditMapper audits, DataScopeResolver scopes,
                               EnterpriseVerificationMapper verifications) {
        this.agentLeads = agentLeads;
        this.leads = leads;
        this.audits = audits;
        this.scopes = scopes;
        this.verifications = verifications;
    }

    record PendingInput(@NotBlank @Size(max = 36) String runId, @NotNull @Positive Long enterpriseId,
                        @NotNull @Positive Long projectId, @NotEmpty @Size(max = 50) List<@NotNull @Positive Long> signalIds) {}

    @PostMapping("/pending")
    @PreAuthorize("@ss.hasPermission('agent:use') and @ss.hasPermission('lead:create')")
    @Transactional
    public Map<String, Object> create(@Valid @RequestBody PendingInput input,
                                      @AuthenticationPrincipal AccountPrincipal actor) {
        Map<String, Object> run = agentLeads.lockRun(input.runId());
        if (run == null || (((Number) run.get("user_id")).longValue() != actor.id() && !actor.hasRole("ADMIN"))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Agent 任务不存在");
        }
        Long existing = agentLeads.findCreatedLead(input.runId(), input.enterpriseId(), input.projectId());
        if (existing != null) {
            return Rows.view(Rows.required(leads.findById(existing, scopes.leadOwner(actor)), "线索不存在"));
        }
        if (!"APPROVING".equals(run.get("status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "任务尚未获得人工确认或已结束");
        }
        Map<String, Object> score = agentLeads.findScore(input.runId(), input.enterpriseId(), input.projectId());
        if (score == null || "HOLD".equals(score.get("grade"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该企业不在本任务可创建线索的评分结果中");
        }
        Map<String, Object> verification = verifications.latestReal(input.enterpriseId());
        if (verification != null && List.of("INACTIVE", "NOT_FOUND", "MISMATCH").contains(verification.get("verification_status"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "企业主体核验结果异常，请先人工核实");
        }
        Map<String, Object> enterprise = Rows.required(verifications.company(input.enterpriseId()), "企业不存在");
        String status = java.util.Objects.toString(enterprise.get("business_status"), "");
        if (List.of("注销", "吊销", "停业", "清算", "迁出").stream().anyMatch(status::contains)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "企业档案登记状态异常，请先人工核实");
        }
        if (leads.count(null, null, null, input.enterpriseId(), input.projectId(), null, null) > 0
                || agentLeads.reserve(input.runId(), input.enterpriseId(), input.projectId()) != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "企业与项目已有线索或正在创建，请勿重复提交");
        }
        var values = Rows.params("enterprise", input.enterpriseId(), "project", input.projectId(),
                "source", "AGENT", "status", "PENDING_REVIEW", "score", score.get("total_score"),
                "owner", actor.id(), "nextFollowup", null, "actor", actor.id());
        leads.insert(values);
        long leadId = ((Number) values.get("id")).longValue();
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(input.signalIds()));
        if (agentLeads.insertEvidence(leadId, input.enterpriseId(), ids) != ids.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "证据缺失、过期、属于演示数据或不属于该企业");
        }
        agentLeads.attachLead(input.runId(), input.enterpriseId(), input.projectId(), leadId);
        audits.record(actor.id(), "CREATE_PENDING", "LEAD", leadId);
        return Rows.view(Rows.required(leads.findById(leadId, scopes.leadOwner(actor)), "线索不存在"));
    }
}
