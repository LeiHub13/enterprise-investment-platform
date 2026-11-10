package com.enterprise.investment.security;

import com.enterprise.investment.mapper.RoleMapper;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

@Service
public class DataScopeResolver {
    public static final String ALL = "ALL";
    public static final String SELF = "SELF";
    public static final String OWN_LEADS = "OWN_LEADS";

    private final RoleMapper roles;

    DataScopeResolver(RoleMapper roles) {
        this.roles = roles;
    }

    public String scopeOf(AccountPrincipal actor, String resource) {
        List<Map<String, Object>> rows = roles.dataScopesOf(actor.id());
        boolean self = false;
        boolean ownLeads = false;
        for (Map<String, Object> row : rows) {
            if (!resource.equals(row.get("resource"))) {
                continue;
            }
            String scope = String.valueOf(row.get("scope"));
            if (ALL.equals(scope)) {
                return ALL;
            }
            if (OWN_LEADS.equals(scope)) {
                ownLeads = true;
            } else if (SELF.equals(scope)) {
                self = true;
            }
        }
        if (ownLeads) {
            return OWN_LEADS;
        }
        return self ? SELF : ALL;
    }

    public Long ownerFilter(String resource, AccountPrincipal actor) {
        return SELF.equals(scopeOf(actor, resource)) ? actor.id() : null;
    }

    public Long leadOwner(AccountPrincipal actor) {
        return ownerFilter("lead", actor);
    }

    public Long contactOwner(AccountPrincipal actor) {
        return OWN_LEADS.equals(scopeOf(actor, "contact")) ? actor.id() : null;
    }
}
