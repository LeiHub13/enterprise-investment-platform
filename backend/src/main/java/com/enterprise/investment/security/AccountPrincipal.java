package com.enterprise.investment.security;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;
import java.util.Set;

public record AccountPrincipal(long id, String username, String role, Set<String> permissions,
                               List<Menu> menus, @JsonIgnore String credentialVersion) {
    public AccountPrincipal(long id, String username, String role, String credentialVersion) {
        this(id, username, role, Set.of(), List.of(), credentialVersion);
    }

    public AccountPrincipal(long id, String username, String role) {
        this(id, username, role, Set.of(), List.of(), null);
    }

    public boolean hasRole(String code) {
        return code.equals(role);
    }

    public boolean hasPermission(String code) {
        return permissions.contains("*") || permissions.contains(code);
    }

    @Override
    public String toString() {
        return "AccountPrincipal[id=" + id + ", username=" + username + ", role=" + role + "]";
    }

    public record Menu(String key, String label, String group) {
    }
}
