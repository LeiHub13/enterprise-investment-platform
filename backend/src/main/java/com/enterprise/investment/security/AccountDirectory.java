package com.enterprise.investment.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class AccountDirectory {
    static final String USERNAME_PATTERN = "[A-Za-z0-9._-]{3,64}";
    static final String ROLE_PATTERN = "ADMIN|DATA_STEWARD|INVESTMENT_MANAGER|INVESTMENT_OFFICER";

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final String absentAccountHash;

    AccountDirectory(JdbcTemplate jdbc, PasswordEncoder passwords) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.absentAccountHash = passwords.encode(UUID.randomUUID().toString());
    }

    Optional<AccountPrincipal> authenticate(String username, String password) {
        Optional<StoredAccount> account = jdbc.query("""
                SELECT id, username, role, password_hash FROM sys_user
                WHERE username = ? AND enabled = TRUE
                """, (row, index) -> new StoredAccount(
                new AccountPrincipal(row.getLong("id"), row.getString("username"), row.getString("role")),
                row.getString("password_hash")), username).stream().findFirst();

        String hash = account.map(StoredAccount::passwordHash).orElse(absentAccountHash);
        return passwords.matches(password, hash) ? account.map(stored -> new AccountPrincipal(
                stored.principal().id(), stored.principal().username(), stored.principal().role(),
                version(stored.passwordHash()))) : Optional.empty();
    }

    List<AccountSummary> list(boolean administrator) {
        return jdbc.query("""
                SELECT id, username, role, enabled FROM sys_user
                WHERE ? OR (enabled = TRUE AND role <> 'DATA_STEWARD')
                ORDER BY username, id
                """, (row, index) -> new AccountSummary(
                row.getLong("id"), row.getString("username"), row.getString("role"), row.getBoolean("enabled")),
                administrator);
    }

    boolean isCurrent(AccountPrincipal actor) {
        return jdbc.query("""
                SELECT username, role, password_hash FROM sys_user WHERE id = ? AND enabled = TRUE
                """, (row, index) -> actor.username().equals(row.getString("username"))
                && actor.role().equals(row.getString("role"))
                && version(row.getString("password_hash")).equals(actor.credentialVersion()), actor.id())
                .stream().findFirst().orElse(false);
    }

    AccountPrincipal create(String username, String password, String role) {
        String hash = passwords.encode(password);
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO sys_user (username, password_hash, role, enabled) VALUES (?, ?, ?, TRUE)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, username);
            statement.setString(2, hash);
            statement.setString(3, role);
            return statement;
        }, keys);
        return new AccountPrincipal(Objects.requireNonNull(keys.getKey()).longValue(), username, role);
    }

    @Transactional
    public AccountPrincipal createAs(AccountPrincipal actor, String username, String password, String role) {
        lockAdministrators(actor);
        AccountPrincipal account = create(username, password, role);
        jdbc.update("""
                INSERT INTO audit_log (user_id, operation, resource_type, resource_id)
                VALUES (?, 'USER_CREATE', 'sys_user', ?)
                """, actor.id(), account.id());
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
            jdbc.update("UPDATE sys_user SET enabled = ? WHERE id = ?", enabled, id);
            audit(actor.id(), enabled ? "USER_ENABLE" : "USER_DISABLE", id);
        }
    }

    private List<Map<String, Object>> lockAdministrators(AccountPrincipal actor) {
        List<Map<String, Object>> admins = jdbc.queryForList("""
                SELECT id, password_hash FROM sys_user
                WHERE role = 'ADMIN' AND enabled = TRUE ORDER BY id FOR UPDATE
                """);
        boolean current = admins.stream().anyMatch(row ->
                ((Number) row.get("id")).longValue() == actor.id()
                        && version((String) row.get("password_hash")).equals(actor.credentialVersion()));
        if (!current) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "管理员账号状态已改变，请重新登录");
        }
        return admins;
    }

    private Map<String, Object> lockAccount(long id) {
        return jdbc.queryForList("SELECT id, role, enabled, password_hash FROM sys_user WHERE id = ? FOR UPDATE", id)
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "账号不存在"));
    }

    private void updatePassword(long id, String password) {
        jdbc.update("UPDATE sys_user SET password_hash = ? WHERE id = ?", passwords.encode(password), id);
    }

    private static boolean isEnabled(Object value) {
        return Boolean.TRUE.equals(value) || value instanceof Number number && number.intValue() != 0;
    }

    private void audit(long actor, String operation, long target) {
        jdbc.update("""
                INSERT INTO audit_log (user_id, operation, resource_type, resource_id)
                VALUES (?, ?, 'sys_user', ?)
                """, actor, operation, target);
    }

    private static String version(String hash) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(hash.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    void bootstrap(String username, String password) {
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
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM sys_user WHERE enabled = TRUE AND role = 'ADMIN')", Boolean.class));
    }

    private record StoredAccount(AccountPrincipal principal, String passwordHash) {
    }

    record AccountSummary(long id, String username, String role, boolean enabled) {
    }
}
