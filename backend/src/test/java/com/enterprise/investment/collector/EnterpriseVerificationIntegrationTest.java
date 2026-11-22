package com.enterprise.investment.collector;

import com.enterprise.investment.business.EnterpriseController;
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
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "AGENT_IT_DB_URL", matches = ".+")
class EnterpriseVerificationIntegrationTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("AGENT_IT_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv("AGENT_IT_DB_USER"));
        registry.add("spring.datasource.password", () -> System.getenv("AGENT_IT_DB_PASSWORD"));
        registry.add("app.bootstrap-admin.username", () -> "agent_test_admin");
        registry.add("app.bootstrap-admin.password", () -> "LocalAgentTestPassword123!");
    }
    @Autowired JdbcTemplate db;
    @Autowired EnterpriseVerificationController worker;
    @Autowired EnterpriseController companies;

    @AfterEach void clearAuthentication() { SecurityContextHolder.clearContext(); }

    private void authenticate() {
        long id = db.queryForObject("SELECT id FROM sys_user WHERE username='agent_test_admin'", Long.class);
        var actor = new AccountPrincipal(id, "agent_test_admin", "ADMIN", Set.of("*"), List.of(), null);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(actor, "", List.of()));
    }

    private long company(String name) {
        db.update("INSERT INTO ent_enterprise(name,name_normalized) VALUES (?,?)", name, name);
        return db.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private EnterpriseVerificationController.VerificationInput input(long id, String order, boolean demo,
            boolean found, String name, String status) {
        return new EnterpriseVerificationController.VerificationInput(id, order, demo, found, name,
                "913200000000000001", status, "【自动化测试】地址");
    }

    @Test
    void liveVerificationIsIdempotentDemoDoesNotOverrideAndOldResultExpires() {
        authenticate();
        String name = "【测试企业】" + UUID.randomUUID();
        long id = company(name);
        String order = UUID.randomUUID().toString();
        var first = worker.ingest(input(id, order, false, true, name, "存续"));
        assertEquals("VERIFIED", first.get("resultStatus"));
        var duplicate = worker.ingest(input(id, order, false, true, name, "注销"));
        assertEquals(first.get("id"), duplicate.get("id"));
        assertEquals("VERIFIED", duplicate.get("resultStatus"));
        assertEquals("VERIFIED", companies.get(id).get("verificationStatus"));
        var demo = worker.ingest(input(id, UUID.randomUUID().toString(), true, true, "演示", "注销"));
        assertEquals("PENDING", demo.get("resultStatus"));
        assertEquals("VERIFIED", companies.get(id).get("verificationStatus"));
        assertEquals(2, companies.verifications(id).size());
        db.update("UPDATE ent_enterprise_verification SET verified_at=NOW()-INTERVAL 31 DAY WHERE id=?", first.get("id"));
        assertEquals("PENDING", companies.get(id).get("verificationStatus"));
        assertTrue(((String) companies.get(id).get("verificationReason")).contains("超过30天"));
    }

    @Test
    void missingMismatchAndInactiveResultsAreConservative() {
        authenticate();
        String name = "【测试企业】" + UUID.randomUUID();
        long id = company(name);
        assertEquals("MISMATCH", worker.ingest(input(id, UUID.randomUUID().toString(), false, true, "其他企业", "存续")).get("resultStatus"));
        assertEquals("INACTIVE", worker.ingest(input(id, UUID.randomUUID().toString(), false, true, name, "注销")).get("resultStatus"));
        assertEquals("NOT_FOUND", worker.ingest(input(id, UUID.randomUUID().toString(), false, false, null, null)).get("resultStatus"));
        assertEquals("NOT_FOUND", companies.get(id).get("verificationStatus"));
    }

    @Test
    void ambiguousNameCannotBeVerifiedAndOrderCannotMoveBetweenCompanies() {
        authenticate();
        String name = "【同名企业】" + UUID.randomUUID();
        long first = company(name), second = company(name);
        String order = UUID.randomUUID().toString();
        assertEquals("PENDING", worker.ingest(input(first, order, false, true, name, "存续")).get("resultStatus"));
        assertThrows(ResponseStatusException.class, () -> worker.ingest(input(second, order, false, true, name, "存续")));
    }
}
