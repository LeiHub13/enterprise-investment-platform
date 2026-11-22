package com.enterprise.investment.collector;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.enterprise.investment.mapper.DataMapper;
import com.enterprise.investment.business.CompanySignalController;
import com.enterprise.investment.security.AccountPrincipal;
import java.time.LocalDate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
class CollectorOutboxIntegrationTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("AGENT_IT_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("AGENT_IT_DB_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("AGENT_IT_DB_PASSWORD"));
        registry.add("app.bootstrap-admin.username", () -> "agent_test_admin");
        registry.add("app.bootstrap-admin.password", () -> "LocalAgentTestPassword123!");
    }
    @Autowired JdbcTemplate db;
    @Autowired ObjectMapper json;
    @Autowired CollectorController collector;
    @Autowired CollectorOutboxController outbox;
    @Autowired DataMapper data;
    @Autowired CompanySignalController companySignals;

    @AfterEach
    void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test
    void ingestionCreatesOneLeasedDemoEventAndRequiresTheCorrectAckToken() throws Exception {
        String suffix = UUID.randomUUID().toString();
        db.update("INSERT INTO ent_enterprise(name,name_normalized) VALUES (?,?)", "演示企业" + suffix, "演示企业" + suffix);
        long enterprise = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        var input = new CollectorController.RecordInput("QCC", "RECRUITMENT", suffix, enterprise,
                "【演示】招聘快照", "演示数据", null,
                json.readTree("{\"demo\":true,\"posts\":[{\"title\":\"演示岗位\",\"phone\":\"PRIVATE_TEST\"}]}"),
                LocalDate.now(), null, 1, 0.35);
        Map<String, Object> result = collector.ingest(input);
        long record = ((Number) result.get("recordId")).longValue();
        assertEquals(true, result.get("created"));
        assertEquals(false, collector.ingest(input).get("created"));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM collector_event_outbox WHERE source_record_id=?", Integer.class, record));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM ent_company_signal WHERE source_record_id=? AND observed_at IS NULL", Integer.class, record));
        assertTrue(((Number) data.collectorSummary().get("demo_record_count")).intValue() >= 1);
        assertTrue(data.sourceStatus().stream().anyMatch(item -> "QCC".equals(item.get("source"))
                && ((Number) item.get("demo_count")).intValue() >= 1));
        assertTrue(data.listRecords(null, null, null, 100, 0).stream()
                .anyMatch(item -> ((Number) item.get("id")).longValue() == record && ((Number) item.get("is_demo")).intValue() == 1));
        assertTrue(data.listSignals(null, 100, 0).stream()
                .anyMatch(item -> ((Number) item.get("enterprise_id")).longValue() == enterprise && ((Number) item.get("is_demo")).intValue() == 1));
        var principal = new AccountPrincipal(1, "test", "ADMIN", Set.of("*"), List.of(), null);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, "", List.of()));
        var safeSignals = companySignals.list(enterprise);
        assertFalse(safeSignals.toString().contains("PRIVATE_TEST"));
        assertTrue(safeSignals.toString().contains("演示岗位"));
        var event = outbox.claim(new CollectorOutboxController.ClaimInput(100)).stream()
                .filter(item -> ((Number) item.get("sourceRecordId")).longValue() == record).findFirst().orElseThrow();
        long id = ((Number) event.get("id")).longValue();
        assertEquals(1, ((Number) event.get("isDemo")).intValue());
        assertThrows(ResponseStatusException.class, () -> outbox.ack(id, new CollectorOutboxController.LeaseInput("wrong")));
        assertEquals(true, outbox.ack(id, new CollectorOutboxController.LeaseInput((String) event.get("leaseToken"))).get("published"));
        assertEquals("PUBLISHED", db.queryForObject("SELECT status FROM collector_event_outbox WHERE id=?", String.class, id));
    }

    @Test
    void sourceRecordFromAnotherEnterpriseIsNotExposedAsEvidence() throws Exception {
        String suffix = UUID.randomUUID().toString();
        db.update("INSERT INTO ent_enterprise(name,name_normalized) VALUES (?,?)", "来源企业" + suffix, "来源企业" + suffix);
        long sourceEnterprise = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        db.update("INSERT INTO ent_enterprise(name,name_normalized) VALUES (?,?)", "目标企业" + suffix, "目标企业" + suffix);
        long otherEnterprise = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        var input = new CollectorController.RecordInput("QCC", "RECRUITMENT", suffix, sourceEnterprise,
                "来源快照", "来源", null, json.readTree("{\"posts\":[{\"title\":\"OTHER_COMPANY_JOB\"}]}"),
                LocalDate.now(), null, 1, 0.8);
        long record = ((Number) collector.ingest(input).get("recordId")).longValue();
        db.update("INSERT INTO ent_company_signal (enterprise_id, signal_type, title, source_record_id, observed_at, strength) VALUES (?, 'RECRUITMENT', '错误关联', ?, ?, 1)",
                otherEnterprise, record, LocalDate.now());
        var principal = new AccountPrincipal(1, "test", "ADMIN", Set.of("*"), List.of(), null);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, "", List.of()));
        var result = companySignals.list(otherEnterprise);
        assertEquals(1, result.size());
        assertNull(result.get(0).get("validityStatus"));
        assertFalse(result.toString().contains("OTHER_COMPANY_JOB"));
    }

    @Test
    void incrementalUpdatesPreserveEvidenceAndEmitOneExpiryEvent() throws Exception {
        String suffix = UUID.randomUUID().toString();
        db.update("INSERT INTO ent_enterprise(name,name_normalized) VALUES (?,?)", "增量测试" + suffix, "增量测试" + suffix);
        long enterprise = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        Instant firstTime = Instant.now().minusSeconds(120).truncatedTo(ChronoUnit.SECONDS);
        Instant expires = Instant.now().plusSeconds(3600).truncatedTo(ChronoUnit.SECONDS);
        var original = new CollectorController.RecordInput("MANUAL", "RENTAL", suffix, enterprise,
                "测试求租", "范围 A", null, json.readTree("{\"a\":1,\"b\":2}"),
                LocalDate.now(), LocalDate.now(), 1, 0.8, expires, firstTime);
        var reordered = new CollectorController.RecordInput("MANUAL", "RENTAL", suffix, enterprise,
                "测试求租", "范围 A", null, json.readTree("{\"b\":2.0,\"a\":1}"),
                LocalDate.now(), LocalDate.now(), 1, 0.8, expires, firstTime.plusSeconds(10));
        var changed = new CollectorController.RecordInput("MANUAL", "RENTAL", suffix, enterprise,
                "测试求租", "范围 B", null, json.readTree("{\"a\":1,\"b\":2}"),
                LocalDate.now(), LocalDate.now(), 1, 0.8, expires, firstTime.plusSeconds(20));
        Map<String, Object> initial = collector.ingest(original);
        long oldRecord = ((Number) initial.get("recordId")).longValue();
        assertEquals("NEW", initial.get("changeType"));
        assertEquals("UNCHANGED", collector.ingest(reordered).get("changeType"));
        Map<String, Object> update = collector.ingest(changed);
        long newRecord = ((Number) update.get("recordId")).longValue();
        assertEquals("UPDATED", update.get("changeType"));
        assertEquals(2, update.get("version"));
        assertEquals("SUPERSEDED", db.queryForObject("SELECT validity_status FROM collector_record WHERE id=?", String.class, oldRecord));
        assertEquals("VALID", db.queryForObject("SELECT validity_status FROM collector_record WHERE id=?", String.class, newRecord));
        assertEquals("STALE", collector.ingest(original).get("changeType"));
        assertEquals("UNCHANGED", collector.ingest(changed).get("changeType"));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM collector_event_outbox WHERE source_record_id=? AND event_type='COLLECTOR_RECORD_UPDATED'", Integer.class, newRecord));

        var conflicting = new CollectorController.RecordInput("MANUAL", "RENTAL", suffix, enterprise,
                "测试求租", "时间不明的不同内容", null, json.readTree("{\"a\":1}"),
                LocalDate.now(), LocalDate.now(), 1, 0.8, expires, null);
        assertThrows(ResponseStatusException.class, () -> collector.ingest(conflicting));
        var demo = new CollectorController.RecordInput("MANUAL", "RENTAL", suffix, enterprise,
                "演示求租", "演示", null, json.readTree("{\"demo\":true}"),
                LocalDate.now(), LocalDate.now(), 1, 0.35, expires, firstTime);
        assertEquals("NEW", collector.ingest(demo).get("changeType"));
        assertEquals("VALID", db.queryForObject("SELECT validity_status FROM collector_record WHERE id=?", String.class, newRecord));

        db.update("UPDATE ent_company_signal SET expires_at=NOW() - INTERVAL 1 MINUTE WHERE source_record_id=?", newRecord);
        var principal = new AccountPrincipal(1, "test", "ADMIN", Set.of("*"), List.of(), null);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(principal, "", List.of()));
        assertTrue(companySignals.list(enterprise).stream().anyMatch(signal ->
                signal.get("sourceRecordId").equals(newRecord) && "EXPIRED".equals(signal.get("validityStatus"))));
        collector.expire(new CollectorController.ExpireInput(100));
        collector.expire(new CollectorController.ExpireInput(100));
        assertEquals("EXPIRED", db.queryForObject("SELECT validity_status FROM collector_record WHERE id=?", String.class, newRecord));
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM collector_event_outbox WHERE source_record_id=? AND event_type='COLLECTOR_RECORD_EXPIRED'", Integer.class, newRecord));
    }

    @Test
    void oldUnversionedRecordIsAdoptedWithoutLosingItsIdentity() throws Exception {
        String suffix = UUID.randomUUID().toString();
        db.update("INSERT INTO ent_enterprise(name,name_normalized) VALUES (?,?)", "旧数据测试" + suffix, "旧数据测试" + suffix);
        long enterprise = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        String hash = SnapshotFingerprint.sha256("QCC|" + suffix + "|RECRUITMENT|" + enterprise);
        db.update("INSERT INTO collector_record(source,external_id,record_type,enterprise_id,title,summary,raw_payload,content_hash,published_at) "
                + "VALUES ('QCC',?,'RECRUITMENT',?,'旧招聘','旧摘要',JSON_OBJECT('demo',FALSE),?,CURRENT_DATE)", suffix, enterprise, hash);
        long record = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        db.update("INSERT INTO ent_company_signal(enterprise_id,signal_type,title,detail,source_name,observed_at,strength,source_record_id,confidence) "
                + "VALUES (?,'RECRUITMENT','旧招聘','旧摘要','QCC',CURRENT_DATE,1,?,0.8)", enterprise, record);
        var input = new CollectorController.RecordInput("QCC", "RECRUITMENT", suffix, enterprise,
                "旧招聘", "旧摘要", null, json.readTree("{\"demo\":false}"),
                LocalDate.now(), LocalDate.now(), 1, 0.8);
        Map<String, Object> result = collector.ingest(input);
        assertEquals("UNCHANGED", result.get("changeType"));
        assertEquals(record, ((Number) result.get("recordId")).longValue());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM collector_record WHERE enterprise_id=?", Integer.class, enterprise));
    }

    @Test
    void sourceUpdatesWithinOneSecondRemainOrdered() throws Exception {
        String suffix = UUID.randomUUID().toString();
        db.update("INSERT INTO ent_enterprise(name,name_normalized) VALUES (?,?)", "时间精度测试" + suffix, "时间精度测试" + suffix);
        long enterprise = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        Instant first = Instant.now().minusSeconds(10).truncatedTo(ChronoUnit.SECONDS);
        var initial = new CollectorController.RecordInput("MANUAL", "RENTAL", suffix, enterprise,
                "求租测试", "版本 A", null, null, LocalDate.now(), LocalDate.now(), 1, 0.8, null, first);
        var later = new CollectorController.RecordInput("MANUAL", "RENTAL", suffix, enterprise,
                "求租测试", "版本 B", null, null, LocalDate.now(), LocalDate.now(), 1, 0.8, null, first.plusMillis(200));
        assertEquals("NEW", collector.ingest(initial).get("changeType"));
        assertEquals("UPDATED", collector.ingest(later).get("changeType"));
        assertEquals("STALE", collector.ingest(initial).get("changeType"));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void simultaneousSubmissionsCreateOnlyOneRecordAndEvent() throws Exception {
        String suffix = UUID.randomUUID().toString();
        db.update("INSERT INTO ent_enterprise(name,name_normalized) VALUES (?,?)", "并发测试" + suffix, "并发测试" + suffix);
        long enterprise = db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        var input = new CollectorController.RecordInput("QCC", "RECRUITMENT", suffix, enterprise,
                "并发测试", "并发记录", null, json.readTree("{\"demo\":true}"),
                LocalDate.now(), LocalDate.now(), 1, 0.35);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            var first = executor.submit(() -> { start.await(); return collector.ingest(input); });
            var second = executor.submit(() -> { start.await(); return collector.ingest(input); });
            start.countDown();
            var a = first.get(20, TimeUnit.SECONDS);
            var b = second.get(20, TimeUnit.SECONDS);
            assertEquals(a.get("recordId"), b.get("recordId"));
            assertNotEquals(a.get("created"), b.get("created"));
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM collector_record WHERE enterprise_id=?", Integer.class, enterprise));
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM collector_event_outbox WHERE source_record_id=?", Integer.class, a.get("recordId")));
        } finally {
            executor.shutdownNow();
            db.update("DELETE FROM ent_enterprise WHERE id=? AND name=?", enterprise, "并发测试" + suffix);
        }
    }
}
