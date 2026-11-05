package com.enterprise.investment.security;

import com.fasterxml.jackson.annotation.JsonIgnore;

public record AccountPrincipal(long id, String username, String role, @JsonIgnore String credentialVersion) {
    public AccountPrincipal(long id, String username, String role) {
        this(id, username, role, null);
    }

    @Override
    public String toString() {
        return "AccountPrincipal[id=" + id + ", username=" + username + ", role=" + role + "]";
    }
}
