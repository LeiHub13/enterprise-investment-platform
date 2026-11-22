package com.enterprise.investment.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionServiceTest {
    private final PermissionService service = new PermissionService();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void checksPermissionCodesFromPrincipal() {
        var principal = new AccountPrincipal(7, "officer", "INVESTMENT_OFFICER",
                Set.of("lead:view", "lead:update"), List.of(), "version");
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));

        assertTrue(service.hasPermission("lead:view"));
        assertTrue(service.hasAnyPermissions("lead:delete", "lead:update"));
        assertFalse(service.hasPermission("lead:delete"));
        assertFalse(service.hasAnyPermissions("user:manage"));
    }

    @Test
    void rejectsMissingAuthentication() {
        assertFalse(service.hasPermission("lead:view"));
        assertFalse(service.hasAnyPermissions("lead:view", "user:view"));
    }
}
