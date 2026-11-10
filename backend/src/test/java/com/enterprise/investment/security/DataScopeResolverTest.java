package com.enterprise.investment.security;

import com.enterprise.investment.mapper.RoleMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DataScopeResolverTest {
    private final RoleMapper roles = mock(RoleMapper.class);
    private final DataScopeResolver resolver = new DataScopeResolver(roles);
    private final AccountPrincipal officer = new AccountPrincipal(7, "officer", "INVESTMENT_OFFICER",
            Set.of(), List.of(), "version");

    @Test
    void officerSeesOnlyOwnLeadsAndOwnLeadContacts() {
        when(roles.dataScopesOf(7)).thenReturn(List.of(
                scope("lead", "SELF"), scope("project", "ALL"), scope("contact", "OWN_LEADS")));

        assertEquals(7L, resolver.leadOwner(officer));
        assertEquals(7L, resolver.contactOwner(officer));
        assertEquals(DataScopeResolver.SELF, resolver.scopeOf(officer, "lead"));
    }

    @Test
    void allScopeWinsAcrossRoles() {
        when(roles.dataScopesOf(7)).thenReturn(List.of(
                scope("lead", "SELF"), scope("lead", "ALL")));

        assertNull(resolver.leadOwner(officer));
        assertEquals(DataScopeResolver.ALL, resolver.scopeOf(officer, "lead"));
    }

    @Test
    void missingScopeDefaultsToAll() {
        when(roles.dataScopesOf(7)).thenReturn(List.of());

        assertNull(resolver.leadOwner(officer));
        assertNull(resolver.contactOwner(officer));
    }

    private static Map<String, Object> scope(String resource, String scope) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("resource", resource);
        row.put("scope", scope);
        return row;
    }
}
