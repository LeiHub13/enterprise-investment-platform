package com.enterprise.investment.security;

import com.enterprise.investment.mapper.AccountMapper;
import com.enterprise.investment.mapper.AuditMapper;
import com.enterprise.investment.mapper.RoleMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class AccountDirectory {
    static final String USERNAME_PATTERN = "[A-Za-z0-9._-]{3,64}";

    private final AccountMapper accounts;
    private final AuditMapper audits;
    private final RoleMapper roles;
    private final PasswordEncoder passwords;
    private final String absentAccountHash;

    AccountDirectory(AccountMapper accounts, AuditMapper audits, RoleMapper roles, PasswordEncoder passwords) {
        this.accounts = accounts;
        this.audits = audits;
        this.roles = roles;
        this.passwords = passwords;
        this.absentAccountHash = passwords.encode(UUID.randomUUID().toString());
    }

    Optional<AccountPrincipal> authenticate(String username, String password) {
        Map<String, Object> account = accounts.findByUsername(username);
        String hash = account == null ? absentAccountHash : (String) account.get("password_hash");
        boolean matches = passwords.matches(password, hash);
        if (account == null || !matches) {
            return Optional.empty();
        }
        long id = ((Number) account.get("id")).longValue();
        return Optional.of(principal(id, (String) account.get("username"),
                (String) account.get("role"), hash));
    }

    Optional<AccountPrincipal> loadPrincipal(long id) {
        Map<String, Object> account = accounts.findCurrent(id);
        if (account == null) {
            return Optional.empty();
        }
        return Optional.of(principal(id, (String) account.get("username"),
                (String) account.get("role"), (String) account.get("password_hash")));
    }

    private AccountPrincipal principal(long id, String username, String role, String passwordHash) {
        Set<String> permissions = new HashSet<>(roles.permissionCodesOf(id));
        List<AccountPrincipal.Menu> menus = roles.menusOf(id).stream()
                .map(row -> new AccountPrincipal.Menu(
                        text(row, "menuKey", "menu_key"),
                        text(row, "label", "label"),
                        text(row, "menuGroup", "menu_group")))
                .toList();
        return new AccountPrincipal(id, username, role, permissions, menus, version(passwordHash));
    }

    private static String text(Map<String, Object> row, String camel, String snake) {
        Object value = row.get(camel);
        if (value == null) {
            value = row.get(snake);
        }
        return value == null ? "" : String.valueOf(value);
    }

    List<AccountSummary> list(boolean administrator) {
        return accounts.list(administrator).stream()
                .map(row -> new AccountSummary(((Number) row.get("id")).longValue(),
                        (String) row.get("username"), (String) row.get("role"), isEnabled(row.get("enabled"))))
                .toList();
    }

    AccountPrincipal create(String username, String password, String role) {
        Long roleId = roles.findEnabledRoleIdByCode(role);
        if (roleId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色不存在或已停用");
        }
        String hash = passwords.encode(password);
        Map<String, Object> user = new HashMap<>();
        user.put("username", username);
        user.put("passwordHash", hash);
        user.put("role", role);
        accounts.insert(user);
        long id = ((Number) user.get("id")).longValue();
        roles.insertUserRole(id, roleId);
        return new AccountPrincipal(id, username, role);
    }

    @Transactional
    public AccountPrincipal createAs(AccountPrincipal actor, String username, String password, String role) {
        lockAdministrators(actor);
        AccountPrincipal account = create(username, password, role);
        audits.record(actor.id(), "USER_CREATE", "sys_user", account.id());
        return account;
    }

    @Transactional
    public void changeOwnPassword(AccountPrincipal actor, String currentPassword, String newPassword) {
        Map<String, Object> row = lockAccount(actor.id());
        if (!isEnabled(row.get("enabled"))
                || !actor.credentialVersion().equals(version((String) row.get("password_hash")))
                || !passwords.matches(currentPassword, (String) row.get("password_hash"))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "原密码不正确或账号状态已改变");
        }
        updatePassword(actor.id(), newPassword);
        audit(actor.id(), "PASSWORD_CHANGE", actor.id());
    }

    @Transactional
    public void resetPassword(AccountPrincipal actor, long id, String newPassword) {
        lockAdministrators(actor);
        lockAccount(id);
        updatePassword(id, newPassword);
        audit(actor.id(), "USER_PASSWORD_RESET", id);
    }

    @Transactional
    public void changeRole(AccountPrincipal actor, long id, String role) {
        lockAdministrators(actor);
        lockAccount(id);
        if (actor.id() == id) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "不能修改当前登录账号的角色");
        }
        Long roleId = roles.findEnabledRoleIdByCode(role);
        if (roleId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "角色不存在或已停用");
        }
        accounts.updateRole(id, role);
        roles.deleteUserRoles(id);
        roles.insertUserRole(id, roleId);
        audit(actor.id(), "USER_ROLE", id);
    }

    @Transactional
    public void setEnabled(AccountPrincipal actor, long id, boolean enabled) {
        List<Map<String, Object>> admins = lockAdministrators(actor);
        Map<String, Object> target = lockAccount(id);
        if (!enabled && actor.id() == id) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "不能停用当前登录账号");
        }
        if (!enabled && "ADMIN".equals(target.get("role")) && isEnabled(target.get("enabled"))
                && admins.size() <= 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "至少需要保留一个启用的管理员");
        }
        if (enabled != isEnabled(target.get("enabled"))) {
            accounts.updateEnabled(id, enabled);
            audit(actor.id(), enabled ? "USER_ENABLE" : "USER_DISABLE", id);
        }
    }

    private List<Map<String, Object>> lockAdministrators(AccountPrincipal actor) {
        List<Map<String, Object>> admins = accounts.lockAdministrators();
        boolean current = admins.stream().anyMatch(row ->
                ((Number) row.get("id")).longValue() == actor.id()
                        && version((String) row.get("password_hash")).equals(actor.credentialVersion()));
        if (!current) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "管理员账号状态已改变，请重新登录");
        }
        return admins;
    }

    private Map<String, Object> lockAccount(long id) {
        Map<String, Object> row = accounts.lockById(id);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "账号不存在");
        }
        return row;
    }

    private void updatePassword(long id, String password) {
        accounts.updatePassword(id, passwords.encode(password));
    }

    private static boolean isEnabled(Object value) {
        return Boolean.TRUE.equals(value) || value instanceof Number number && number.intValue() != 0;
    }

    private void audit(long actor, String operation, long target) {
        audits.record(actor, operation, "sys_user", target);
    }

    private static String version(String hash) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(hash.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    @Transactional
    public void bootstrap(String username, String password) {
        if (hasEnabledAdmin()) {
            return;
        }
        if (!username.matches(USERNAME_PATTERN) || !isNewPasswordValid(password)) {
            throw new IllegalStateException(
                    "Set APP_ADMIN_USERNAME and APP_ADMIN_PASSWORD before the first start (password at least 12 characters, at most 72 UTF-8 bytes).");
        }
        try {
            create(username, password, "ADMIN");
        } catch (DuplicateKeyException conflict) {
            if (!hasEnabledAdmin()) {
                throw new IllegalStateException("APP_ADMIN_USERNAME is already in use. Choose an unused username.", conflict);
            }
        }
    }

    static boolean isNewPasswordValid(String password) {
        return !password.isBlank() && password.length() >= 12 && fitsBcrypt(password);
    }

    static boolean fitsBcrypt(String password) {
        return password.getBytes(StandardCharsets.UTF_8).length <= 72;
    }

    private boolean hasEnabledAdmin() {
        return accounts.countEnabledAdmins() > 0;
    }

    record AccountSummary(long id, String username, String role, boolean enabled) {
    }
}
