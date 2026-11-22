package com.enterprise.investment.agent;

import com.enterprise.investment.business.LeadController;
import com.enterprise.investment.mapper.DataMapper;
import com.enterprise.investment.mapper.AgentMapper;
import com.enterprise.investment.security.AccountPrincipal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "AGENT_IT_DB_URL", matches = ".+")
class AgentPendingLeadIntegrationTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("AGENT_IT_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("AGENT_IT_DB_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("AGENT_IT_DB_PASSWORD"));
        registry.add("app.bootstrap-admin.username", () -> "agent_test_admin");
        registry.add("app.bootstrap-admin.password", () -> "LocalAgentTestPassword123!");
    }

    @Autowired JdbcTemplate db;
    @Autowired AgentPendingLeadController pending;
    @Autowired LeadController leads;
    @Autowired DataMapper data;
    @Autowired AgentMapper agent;
    @Autowired AgentProxyController proxy;

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void confirmationCreatesIdempotentPendingLeadWithEvidenceThenReviewerApproves() {
        long user = db.queryForObject("SELECT id FROM sys_user WHERE username='agent_test_admin'", Long.class);
        var actor = new AccountPrincipal(user, "agent_test_admin", "ADMIN", Set.of("*"), List.of(), null);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor, "", List.of()));
        String suffix = UUID.randomUUID().toString();
        db.update("INSERT INTO ent_enterprise(name,name_normalized) VALUES (?,?)", "测试企业" + suffix, "测试企业" + suffix);
        long enterprise = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        long project = db.queryForObject("SELECT id FROM crm_project WHERE project_name='南京鼓楼示范写字楼' LIMIT 1", Long.class);
        String run = UUID.randomUUID().toString();
        String conversation = UUID.randomUUID().toString();
        db.update("INSERT INTO agent_conversation(id,user_id,title) VALUES (?,?,?)", conversation, user, "测试");
        db.update("INSERT INTO agent_run(id,conversation_id,user_id,project_id,message,status) VALUES (?,?,?,?,?,?)",
                run, conversation, user, project, "测试", "AWAITING_REVIEW");
        db.update("INSERT INTO crm_lead_score(run_id,enterprise_id,project_id,total_score,grade,fit_score,intent_score,confidence_score,score_model_version) "
                + "VALUES (?,?,?,?,?,?,?,?,?)", run, enterprise, project, 70, "VERIFY", 70, 70, 70, "V2");
        db.update("INSERT INTO collector_record(source,external_id,record_type,enterprise_id,title,raw_payload,content_hash,published_at) "
                + "VALUES ('QCC',?,'RECRUITMENT',?,'测试记录',JSON_OBJECT('demo',FALSE),SHA2(?,256),CURRENT_DATE)",
                suffix, enterprise, suffix);
        long record = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        db.update("INSERT INTO ent_company_signal(enterprise_id,signal_type,title,source_name,observed_at,strength,source_record_id) "
                + "VALUES (?,'RECRUITMENT','测试记录','QCC',CURRENT_DATE,1,?)", enterprise, record);
        long signal = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        var input = new AgentPendingLeadController.PendingInput(run, enterprise, project, List.of(signal));

        assertThrows(ResponseStatusException.class, () -> pending.create(input, actor));
        db.update("UPDATE agent_run SET status='APPROVING' WHERE id=?", run);
        db.update("INSERT INTO ent_enterprise_verification(enterprise_id,source,order_ref,is_demo,result_status,reason) "
                + "VALUES (?,'QCC_2001',?,FALSE,'MISMATCH','隔离测试')", enterprise, suffix);
        assertThrows(ResponseStatusException.class, () -> pending.create(input, actor));
        db.update("DELETE FROM ent_enterprise_verification WHERE enterprise_id=?", enterprise);
        db.update("UPDATE ent_enterprise SET business_status='注销' WHERE id=?", enterprise);
        assertThrows(ResponseStatusException.class, () -> pending.create(input, actor));
        db.update("UPDATE ent_enterprise SET business_status='存续' WHERE id=?", enterprise);
        var created = pending.create(input, actor);
        long lead = ((Number) created.get("id")).longValue();
        assertEquals("PENDING_REVIEW", created.get("leadStatus"));
        assertEquals(lead, ((Number) pending.create(input, actor).get("id")).longValue());
        assertEquals(1, leads.evidence(lead, actor).size());
        assertEquals(70, ((Number) leads.analysis(lead, actor).get("fitScore")).intValue());
        assertEquals("NEW", leads.approve(lead, actor).get("leadStatus"));
        assertThrows(ResponseStatusException.class, () -> leads.approve(lead, actor));
    }

    @Test
    void cannotCreateAgentLeadWithoutLeadPermission() {
        var actor = new AccountPrincipal(1, "test", "INVESTMENT_OFFICER", Set.of("agent:use"), List.of(), null);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor, "", List.of()));
        var input = new AgentPendingLeadController.PendingInput(UUID.randomUUID().toString(), 1L, 1L, List.of(1L));
        assertThrows(AccessDeniedException.class, () -> pending.create(input, actor));
        assertThrows(AccessDeniedException.class, () -> proxy.eventRecords());
    }

    @Test
    void suppliedCreditCodeNeverFallsBackToAnAmbiguousName() {
        String name = "同名测试企业" + UUID.randomUUID();
        db.update("INSERT INTO ent_enterprise(name,name_normalized) VALUES (?,?),(?,?)", name, name, name, name);
        assertEquals(2, data.resolveCompany(null, name).size());
        assertTrue(data.resolveCompany("91320100MA00000001", name).isEmpty());
    }

    @Test
    void conversationHistoryIsRestrictedToItsOwner() {
        long user = db.queryForObject("SELECT id FROM sys_user WHERE username='agent_test_admin'", Long.class);
        String conversation = UUID.randomUUID().toString();
        agent.upsertConversation(conversation, user, "原会话");
        db.update("UPDATE agent_conversation SET updated_at=NOW() - INTERVAL 2 DAY WHERE id=?", conversation);
        agent.upsertConversation(conversation, user + 10000, "不应覆盖");
        assertEquals("原会话", db.queryForObject("SELECT title FROM agent_conversation WHERE id=?", String.class, conversation));
        assertEquals(user, agent.conversationOwner(conversation));
        String run = UUID.randomUUID().toString();
        db.update("INSERT INTO agent_run(id,conversation_id,user_id,message,status,report) VALUES (?,?,?,?,?,?)",
                run, conversation, user, "上一问", "DONE", "上一报告");
        assertEquals(1, agent.conversationHistory(conversation, user).size());
        assertTrue(agent.conversationHistory(conversation, user + 10000).isEmpty());
        String pendingRun = UUID.randomUUID().toString();
        String otherRun = UUID.randomUUID().toString();
        db.update("INSERT INTO agent_run(id,conversation_id,user_id,message,status) VALUES (?,?,?,?,?)",
                pendingRun, conversation, user, "待确认", "AWAITING_REVIEW");
        db.update("INSERT INTO agent_run(id,conversation_id,user_id,message,status) VALUES (?,?,?,?,?)",
                otherRun, conversation, user + 10000, "其他用户任务", "AWAITING_REVIEW");
        var pendingRuns = agent.listRuns(user, "AWAITING_REVIEW", 20, 0);
        assertTrue(pendingRuns.stream().anyMatch(row -> pendingRun.equals(row.get("id"))));
        assertTrue(pendingRuns.stream().allMatch(row -> "AWAITING_REVIEW".equals(row.get("status")) && !otherRun.equals(row.get("id"))));
    }

    @Test
    void numericDemoMarkerCannotBecomeLeadEvidence() {
        long user = db.queryForObject("SELECT id FROM sys_user WHERE username='agent_test_admin'", Long.class);
        var actor = new AccountPrincipal(user, "agent_test_admin", "ADMIN", Set.of("*"), List.of(), null);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor, "", List.of()));
        String suffix = UUID.randomUUID().toString();
        db.update("INSERT INTO ent_enterprise(name,name_normalized) VALUES (?,?)", "演示测试" + suffix, "演示测试" + suffix);
        long enterprise = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        long project = db.queryForObject("SELECT id FROM crm_project WHERE project_name='南京鼓楼示范写字楼' LIMIT 1", Long.class);
        String run = UUID.randomUUID().toString();
        String conversation = UUID.randomUUID().toString();
        db.update("INSERT INTO agent_conversation(id,user_id,title) VALUES (?,?,?)", conversation, user, "演示测试");
        db.update("INSERT INTO agent_run(id,conversation_id,user_id,project_id,message,status) VALUES (?,?,?,?,?,?)",
                run, conversation, user, project, "演示测试", "APPROVING");
        db.update("INSERT INTO crm_lead_score(run_id,enterprise_id,project_id,total_score,grade,fit_score,intent_score,confidence_score,score_model_version) "
                + "VALUES (?,?,?,?,?,?,?,?,?)", run, enterprise, project, 70, "VERIFY", 70, 70, 70, "V2");
        db.update("INSERT INTO collector_record(source,external_id,record_type,enterprise_id,title,raw_payload,content_hash,published_at) "
                + "VALUES ('MANUAL',?,'RECRUITMENT',?,'演示记录',JSON_OBJECT('demo',1),SHA2(?,256),CURRENT_DATE)", suffix, enterprise, suffix);
        long record = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        db.update("INSERT INTO ent_company_signal(enterprise_id,signal_type,title,source_name,observed_at,strength,source_record_id) "
                + "VALUES (?,'RECRUITMENT','演示记录','MANUAL',CURRENT_DATE,1,?)", enterprise, record);
        long signal = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        assertThrows(ResponseStatusException.class, () -> pending.create(
                new AgentPendingLeadController.PendingInput(run, enterprise, project, List.of(signal)), actor));
    }
}
