package com.enterprise.investment.collector;

import com.enterprise.investment.security.AccountPrincipal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "AGENT_IT_DB_URL", matches = ".+")
class CollectorMonitorIntegrationTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("AGENT_IT_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("AGENT_IT_DB_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("AGENT_IT_DB_PASSWORD"));
        registry.add("app.bootstrap-admin.username", () -> "agent_test_admin");
        registry.add("app.bootstrap-admin.password", () -> "LocalAgentTestPassword123!");
    }
    @Autowired JdbcTemplate db;
    @Autowired MonitorController admin;
    @Autowired CollectorMonitorController worker;

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    private AccountPrincipal actor() {
        long id = db.queryForObject("SELECT id FROM sys_user WHERE username='agent_test_admin'", Long.class);
        var actor = new AccountPrincipal(id, "agent_test_admin", "ADMIN", Set.of("*"), List.of(), null);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor, "", List.of()));
        return actor;
    }

    private long newTask(AccountPrincipal actor) {
        String name = "监测测试" + UUID.randomUUID();
        db.update("INSERT INTO ent_enterprise(name,name_normalized) VALUES (?,?)", name, name);
        long company = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        Map<String, Object> task = admin.create(new MonitorController.CreateInput(company, "DEMO", 1440, 1), actor);
        return ((Number) task.get("id")).longValue();
    }

    private Map<String, Object> claim(long id) {
        return worker.claim(new CollectorMonitorController.ClaimInput(10)).stream()
                .filter(task -> ((Number) task.get("id")).longValue() == id).findFirst().orElseThrow();
    }

    @Test
    void pausedTaskRequiresExplicitEnableAndOnlyLeaseOwnerCanFinish() {
        var actor = actor();
        long id = newTask(actor);
        assertTrue(worker.claim(new CollectorMonitorController.ClaimInput(10)).stream()
                .noneMatch(task -> ((Number) task.get("id")).longValue() == id));
        admin.update(id, new MonitorController.UpdateInput(1440, 1, true), actor);
        Map<String, Object> leased = claim(id);
        assertEquals("DEMO", leased.get("mode"));
        assertTrue(worker.claim(new CollectorMonitorController.ClaimInput(10)).stream()
                .noneMatch(task -> ((Number) task.get("id")).longValue() == id));
        assertThrows(ResponseStatusException.class, () -> worker.finish(id,
                new CollectorMonitorController.FinishInput("wrong", "SUCCESS", null, 1, 0, 0, false)));
        worker.finish(id, new CollectorMonitorController.FinishInput((String) leased.get("leaseToken"), "SUCCESS", null, 1, 0, 0, false));
        assertEquals("SUCCESS", db.queryForObject("SELECT last_status FROM collector_monitor_task WHERE id=?", String.class, id));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM collector_monitor_cursor WHERE task_id=?", Integer.class, id));
        assertEquals(1, admin.runs(id).size());
    }

    @Test
    void repeatedFailuresPauseTaskAndExpiredLeaseIsAbandoned() {
        var actor = actor();
        long id = newTask(actor);
        admin.update(id, new MonitorController.UpdateInput(60, 1, true), actor);
        Map<String, Object> old = claim(id);
        db.update("UPDATE collector_monitor_task SET lease_until=NOW() - INTERVAL 1 MINUTE WHERE id=?", id);
        Map<String, Object> replacement = claim(id);
        assertNotEquals(old.get("leaseToken"), replacement.get("leaseToken"));
        assertEquals("ABANDONED", db.queryForObject("SELECT status FROM collector_monitor_run WHERE lease_token=?", String.class, old.get("leaseToken")));
        for (int attempt = 0; attempt < 3; attempt++) {
            Map<String, Object> leased = attempt == 0 ? replacement : claim(id);
            worker.finish(id, new CollectorMonitorController.FinishInput((String) leased.get("leaseToken"), "FAILED", "QCC_CONFIG", 0, 0, 0, false));
            db.update("UPDATE collector_monitor_task SET next_run_at=NOW() - INTERVAL 1 MINUTE WHERE id=?", id);
        }
        assertEquals(0, db.queryForObject("SELECT enabled FROM collector_monitor_task WHERE id=?", Integer.class, id));
        assertEquals(3, db.queryForObject("SELECT consecutive_failures FROM collector_monitor_task WHERE id=?", Integer.class, id));
        assertTrue(worker.claim(new CollectorMonitorController.ClaimInput(10)).stream()
                .noneMatch(task -> ((Number) task.get("id")).longValue() == id));
    }

    @Test
    void viewingPermissionAloneCannotCreatePaidMonitoring() {
        var principal = new AccountPrincipal(42, "viewer", "DATA_STEWARD", Set.of("governance:view"), List.of(), null);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, "", List.of()));
        assertThrows(AccessDeniedException.class, () -> admin.create(new MonitorController.CreateInput(1L, "LIVE", 1440, 1), principal));
        assertNotNull(admin.status());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentWorkersCannotClaimTheSameTask() throws Exception {
        var actor = actor();
        long id = newTask(actor);
        long enterprise = db.queryForObject("SELECT enterprise_id FROM collector_monitor_task WHERE id=?", Long.class, id);
        var executor = Executors.newFixedThreadPool(2);
        try {
            admin.update(id, new MonitorController.UpdateInput(1440, 1, true), actor);
            var start = new CountDownLatch(1);
            var a = executor.submit(() -> { start.await(); return worker.claim(new CollectorMonitorController.ClaimInput(10)); });
            var b = executor.submit(() -> { start.await(); return worker.claim(new CollectorMonitorController.ClaimInput(10)); });
            start.countDown();
            long matching = java.util.stream.Stream.concat(a.get(20, TimeUnit.SECONDS).stream(), b.get(20, TimeUnit.SECONDS).stream())
                    .filter(row -> ((Number) row.get("id")).longValue() == id).count();
            assertEquals(1, matching);
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM collector_monitor_run WHERE task_id=?", Integer.class, id));
        } finally {
            executor.shutdownNow();
            db.update("DELETE FROM audit_log WHERE resource_type='MONITOR_TASK' AND resource_id=?", id);
            db.update("DELETE FROM ent_enterprise WHERE id=?", enterprise);
        }
    }
}
