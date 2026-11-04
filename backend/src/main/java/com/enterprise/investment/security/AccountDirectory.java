package com.enterprise.investment.security;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
        return passwords.matches(password, hash) ? account.map(StoredAccount::principal) : Optional.empty();
    }

    List<AccountPrincipal> listEnabled(boolean includeDataStewards) {
        return jdbc.query("""
                SELECT id, username, role FROM sys_user
                WHERE enabled = TRUE AND (? OR role <> 'DATA_STEWARD')
                ORDER BY username, id
                """, (row, index) -> new AccountPrincipal(
                row.getLong("id"), row.getString("username"), row.getString("role")),
                includeDataStewards);
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
        AccountPrincipal account = create(username, password, role);
        jdbc.update("""
                INSERT INTO audit_log (user_id, operation, resource_type, resource_id)
                VALUES (?, 'USER_CREATE', 'sys_user', ?)
                """, actor.id(), account.id());
        return account;
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
}
