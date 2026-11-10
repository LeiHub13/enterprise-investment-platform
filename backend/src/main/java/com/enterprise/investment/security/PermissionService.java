package com.enterprise.investment.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

@Service("ss")
public class PermissionService {
    public boolean hasPermission(String permission) {
        AccountPrincipal principal = current();
        return principal != null && principal.hasPermission(permission);
    }

    public boolean hasAnyPermissions(String... permissions) {
        AccountPrincipal principal = current();
        if (principal == null) {
            return false;
        }
        for (String permission : permissions) {
            if (principal.hasPermission(permission)) {
                return true;
            }
        }
        return false;
    }

    private static AccountPrincipal current() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof AccountPrincipal principal
                ? principal
                : null;
    }
}
