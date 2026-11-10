package com.enterprise.investment.security;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
class AuthController {
    private final AccountDirectory accounts;
    private final TokenService tokens;

    AuthController(AccountDirectory accounts, TokenService tokens) {
        this.accounts = accounts;
        this.tokens = tokens;
    }

    @GetMapping("/csrf")
    CsrfResponse csrf() {
        return new CsrfResponse("X-CSRF-TOKEN", "stateless");
    }

    @PostMapping("/login")
    LoginResponse login(@Valid @RequestBody Credentials credentials, HttpServletRequest request) {
        if (!AccountDirectory.fitsBcrypt(credentials.password())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号或密码不正确");
        }
        AccountPrincipal account = accounts.authenticate(credentials.username(), credentials.password())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号或密码不正确"));

        TokenService.IssuedToken issued = tokens.issue(account.id(),
                request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT));
        return new LoginResponse(issued.token(), issued.expiresAt(), account.id(), account.username(),
                account.role(), account.permissions(), account.menus());
    }

    @GetMapping("/me")
    AccountPrincipal me(@AuthenticationPrincipal AccountPrincipal account) {
        return account;
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        tokens.revoke(TokenService.resolveBearer(authorization));
    }

    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changePassword(@Valid @RequestBody PasswordChange input,
                        @AuthenticationPrincipal AccountPrincipal actor) {
        if (!AccountDirectory.fitsBcrypt(input.currentPassword())
                || !AccountDirectory.isNewPasswordValid(input.newPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新密码至少 12 个字符，且最多 72 个 UTF-8 字节");
        }
        accounts.changeOwnPassword(actor, input.currentPassword(), input.newPassword());
        tokens.revokeAll(actor.id());
    }

    record Credentials(@NotBlank @Pattern(regexp = AccountDirectory.USERNAME_PATTERN) String username,
                       @NotBlank @Size(max = 72) String password) {
    }

    record CsrfResponse(String headerName, String token) {
    }

    record LoginResponse(String token, LocalDateTime expiresAt, long id, String username, String role,
                         Set<String> permissions, List<AccountPrincipal.Menu> menus) {
    }

    record PasswordChange(@NotBlank @Size(max = 72) String currentPassword,
                          @NotBlank @Size(min = 12, max = 72) String newPassword) {
    }
}
