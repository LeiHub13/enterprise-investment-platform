package com.enterprise.investment.agent;

import com.enterprise.investment.security.AccountPrincipal;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "AGENT_IT_DB_URL", matches = ".+")
class AgentEventDispatchIntegrationTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("AGENT_IT_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("AGENT_IT_DB_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("AGENT_IT_DB_PASSWORD"));
        registry.add("app.bootstrap-admin.username", () -> "agent_test_admin");
        registry.add("app.bootstrap-admin.password", () -> "LocalAgentTestPassword123!");
    }

    @Autowired JdbcTemplate db;
    @Autowired AgentEventDispatchService dispatch;

    private long[] event(boolean demo, String type, String status) {
        String suffix = UUID.randomUUID().toString();
        db.update("INSERT INTO ent_enterprise(name,name_normalized) VALUES (?,?)", "事件测试" + suffix, "事件测试" + suffix);
        long company = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        db.update("INSERT INTO collector_record(source,external_id,record_type,enterprise_id,title,raw_payload,content_hash,published_at,validity_status) "
                        + "VALUES ('MANUAL',?,'RECRUITMENT',?,'事件测试',JSON_OBJECT('demo',?),SHA2(?,256),CURRENT_DATE,?)",
                suffix, company, demo, suffix, status);
        long record = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        db.update("INSERT INTO collector_event_outbox(source_record_id,event_type) VALUES (?,?)", record, type);
        return new long[]{record, db.queryForObject("SELECT LAST_INSERT_ID()", Long.class)};
    }

    @Test
    void duplicateEventKeepsOneRunAndEnforcesOwner() {
        long[] source = event(false, "COLLECTOR_RECORD_UPDATED", "VALID");
        var actor = new AccountPrincipal(3001, "owner", "INVESTMENT_OFFICER");
        var first = dispatch.prepare(source[1], source[0], actor);
        var duplicate = dispatch.prepare(source[1], source[0], actor);
        assertTrue(first.created());
        assertFalse(duplicate.created());
        assertEquals(first.runId(), duplicate.runId());
        assertEquals("RUNNING", duplicate.status());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM agent_event_dispatch WHERE outbox_event_id=?", Integer.class, source[1]));
        assertThrows(ResponseStatusException.class, () -> dispatch.prepare(source[1], source[0] + 1, actor));
        assertThrows(ResponseStatusException.class, () -> dispatch.prepare(source[1], source[0],
                new AccountPrincipal(3002, "other", "INVESTMENT_OFFICER")));
        db.update("UPDATE agent_run SET status='AWAITING_REVIEW' WHERE id=?", first.runId());
        assertEquals("AWAITING_REVIEW", dispatch.prepare(source[1], source[0], actor).status());
    }

    @Test
    void demoExpiredAndExpiryNotificationsDoNotStartModelWork() {
        var actor = new AccountPrincipal(3001, "owner", "INVESTMENT_OFFICER");
        for (long[] source : new long[][]{
                event(true, "COLLECTOR_RECORD_CREATED", "VALID"),
                event(false, "COLLECTOR_RECORD_CREATED", "EXPIRED"),
                event(false, "COLLECTOR_RECORD_EXPIRED", "VALID")}) {
            var result = dispatch.prepare(source[1], source[0], actor);
            assertFalse(result.created());
            assertEquals("SKIPPED", result.status());
            assertNull(result.runId());
            assertEquals("SKIPPED", dispatch.prepare(source[1], source[0], actor).status());
        }
    }

    @Test
    void abandonedRunEventuallyBecomesAnErrorInsteadOfStartingAnotherRun() {
        long[] source = event(false, "COLLECTOR_RECORD_CREATED", "VALID");
        var actor = new AccountPrincipal(3001, "owner", "INVESTMENT_OFFICER");
        var first = dispatch.prepare(source[1], source[0], actor);
        db.update("UPDATE agent_run SET created_at=NOW() - INTERVAL 16 MINUTE WHERE id=?", first.runId());
        var duplicate = dispatch.prepare(source[1], source[0], actor);
        assertEquals("ERROR", duplicate.status());
        assertEquals(first.runId(), duplicate.runId());
    }
}
