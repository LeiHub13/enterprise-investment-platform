package com.enterprise.investment.openclaw;

import com.enterprise.investment.security.AccountPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OpenClawPermissionTest {
    @AfterEach
    void clearAuthentication() { SecurityContextHolder.clearContext(); }

    @Test
    void openClawControllerRejectsNonAdmin() {
        try (var context = new AnnotationConfigApplicationContext(MethodSecurityConfig.class)) {
            OpenClawController controller = context.getBean(OpenClawController.class);
            AccountPrincipal user = new AccountPrincipal(1, "staff", "INVESTMENT_OFFICER");
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(user, null, List.of()));
            assertThrows(AccessDeniedException.class, controller::status);
        }
    }

    @Test
    void openClawControllerAllowsAdmin() {
        try (var context = new AnnotationConfigApplicationContext(MethodSecurityConfig.class)) {
            OpenClawClient client = context.getBean(OpenClawClient.class);
            when(client.status()).thenReturn(new OpenClawClient.Status(false, "未配置", "session"));
            AccountPrincipal admin = new AccountPrincipal(1, "admin", "ADMIN");
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(admin, null, List.of()));
            assertEquals("未配置", context.getBean(OpenClawController.class).status().message());
        }
    }

    @Test
    void prospectingRejectsNonAdminBeforePaidSearch() {
        try (var context = new AnnotationConfigApplicationContext(MethodSecurityConfig.class)) {
            ProspectingController controller = context.getBean(ProspectingController.class);
            AccountPrincipal user = new AccountPrincipal(1, "staff", "INVESTMENT_OFFICER");
            SecurityContextHolder.getContext().setAuthentication(
                    new UsernamePasswordAuthenticationToken(user, null, List.of()));
            assertThrows(AccessDeniedException.class, () -> controller.find(user,
                    new ProspectingController.Request("session", "泛悦广场", "")));
        }
    }

    @Configuration
    @EnableMethodSecurity
    static class MethodSecurityConfig {
        @Bean OpenClawClient client() { return mock(OpenClawClient.class); }
        @Bean OpenClawController controller(OpenClawClient client) { return new OpenClawController(client); }
        @Bean ProspectingSearch prospectingSearch() { return mock(ProspectingSearch.class); }
        @Bean ProspectingController prospectingController(ProspectingSearch search, OpenClawClient client) {
            return new ProspectingController(search, client, new ObjectMapper());
        }
    }
}
