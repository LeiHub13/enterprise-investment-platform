package com.enterprise.investment.security;

import com.enterprise.investment.mapper.AuditMapper;
import com.enterprise.investment.mapper.RoleMapper;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/roles")
class RoleController {
    private static final String PROTECTED_ROLE = "ADMIN";
    private static final Set<String> RESOURCES = Set.of(
            "lead", "project", "contact", "business", "contract", "receivable");

    private final RoleMapper roles;
    private final AuditMapper audits;

    RoleController(RoleMapper roles, AuditMapper audits) {
        this.roles = roles;
        this.audits = audits;
    }

    @GetMapping
    @PreAuthorize("@ss.hasAnyPermissions('user:view', 'system:role:manage')")
    List<Map<String, Object>> list() {
        return roles.listRoles();
    }

    @GetMapping("/menu-options")
    @PreAuthorize("@ss.hasPermission('system:role:manage')")
    List<Map<String, Object>> menuOptions() {
        return roles.listMenus();
    }

    @GetMapping("/{id}")
    @PreAuthorize("@ss.hasPermission('system:role:manage')")
    RoleDetail detail(@PathVariable long id) {
        return detailOf(requireRole(id));
    }

    @PutMapping("/{id}/permissions")
    @PreAuthorize("@ss.hasPermission('system:role:manage')")
    @Transactional
    RoleDetail updatePermissions(@PathVariable long id, @Valid @RequestBody PermissionUpdate input,
                                 @AuthenticationPrincipal AccountPrincipal actor) {
        requireEditable(id);
        Set<Long> existing = new HashSet<>();
        for (Map<String, Object> menu : roles.listMenus()) {
            existing.add(((Number) menu.get("id")).longValue());
        }
        Set<Long> selected = new HashSet<>(input.menuIds());
        if (selected.size() != input.menuIds().size() || !existing.containsAll(selected)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "权限项不存在或重复");
        }
        if (!selected.contains(2L)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色至少需要保留招商工作台权限");
        }
        roles.deleteRoleMenus(id);
        for (Long menuId : selected) {
            roles.insertRoleMenu(id, menuId);
        }
        audits.record(actor.id(), "ROLE_PERMISSIONS", "sys_role", id);
        return detailOf(requireRole(id));
    }

    @PutMapping("/{id}/data-scopes")
    @PreAuthorize("@ss.hasPermission('system:role:manage')")
    @Transactional
    RoleDetail updateDataScopes(@PathVariable long id, @Valid @RequestBody ScopeUpdate input,
                                @AuthenticationPrincipal AccountPrincipal actor) {
        requireEditable(id);
        Set<String> seen = new HashSet<>();
        for (ScopeInput item : input.scopes()) {
            if (!RESOURCES.contains(item.resource()) || !seen.add(item.resource())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "数据范围资源不存在或重复");
            }
            validateScope(item.resource(), item.scope());
        }
        roles.deleteRoleScopes(id);
        for (ScopeInput item : input.scopes()) {
            roles.insertRoleScope(id, item.resource(), item.scope());
        }
        audits.record(actor.id(), "ROLE_DATA_SCOPES", "sys_role", id);
        return detailOf(requireRole(id));
    }

    private Map<String, Object> requireRole(long id) {
        Map<String, Object> role = roles.findRoleById(id);
        if (role == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "角色不存在");
        }
        return role;
    }

    private Map<String, Object> requireEditable(long id) {
        Map<String, Object> role = requireRole(id);
        if (PROTECTED_ROLE.equals(role.get("code"))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "管理员角色为内置保护角色，不能修改");
        }
        return role;
    }

    private RoleDetail detailOf(Map<String, Object> role) {
        long id = ((Number) role.get("id")).longValue();
        String code = String.valueOf(role.get("code"));
        Map<String, String> scopes = new java.util.LinkedHashMap<>();
        for (Map<String, Object> row : roles.dataScopesOfRole(id)) {
            scopes.put(String.valueOf(row.get("resource")), String.valueOf(row.get("scope")));
        }
        return new RoleDetail(id, code, String.valueOf(role.get("name")), flag(role.get("builtIn")),
                flag(role.get("enabled")), !PROTECTED_ROLE.equals(code),
                roles.menuIdsOfRole(id), scopes);
    }

    private static void validateScope(String resource, String scope) {
        boolean valid = switch (resource) {
            case "lead", "project", "business", "contract", "receivable" ->
                    "ALL".equals(scope) || "SELF".equals(scope);
            case "contact" -> "ALL".equals(scope) || "OWN_LEADS".equals(scope);
            default -> false;
        };
        if (!valid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "数据范围取值无效");
        }
    }

    private static boolean flag(Object value) {
        return Boolean.TRUE.equals(value) || value instanceof Number number && number.intValue() != 0;
    }

    record PermissionUpdate(@NotNull List<Long> menuIds) {
    }

    record ScopeUpdate(@NotNull List<ScopeInput> scopes) {
    }

    record ScopeInput(@NotBlank String resource, @NotBlank String scope) {
    }

    record RoleDetail(long id, String code, String name, boolean builtIn, boolean enabled, boolean editable,
                      List<Long> menuIds, Map<String, String> dataScopes) {
    }
}
