package com.enterprise.investment.security;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/auth")
class AuthController {
    private final AccountDirectory accounts;
    private final HttpSessionSecurityContextRepository contexts;
    private final HttpSessionCsrfTokenRepository tokens;

    AuthController(AccountDirectory accounts, HttpSessionSecurityContextRepository contexts,
                   HttpSessionCsrfTokenRepository tokens) {
        this.accounts = accounts;
        this.contexts = contexts;
        this.tokens = tokens;
    }

    @GetMapping("/csrf")
    CsrfResponse csrf(CsrfToken token) {
        return new CsrfResponse(token.getHeaderName(), token.getToken());
    }

    @PostMapping("/login")
    AccountPrincipal login(@Valid @RequestBody Credentials credentials,
                           HttpServletRequest request, HttpServletResponse response) {
        if (!AccountDirectory.fitsBcrypt(credentials.password())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号或密码不正确");
        }
        AccountPrincipal account = accounts.authenticate(credentials.username(), credentials.password())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "账号或密码不正确"));

        request.getSession();
        request.changeSessionId();
        tokens.saveToken(null, request, response);

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                account, null, List.of(new SimpleGrantedAuthority("ROLE_" + account.role()))));
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        return account;
    }

    @GetMapping("/me")
    AccountPrincipal me(@AuthenticationPrincipal AccountPrincipal account) {
        return account;
    }

    @PutMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void changePassword(@Valid @RequestBody PasswordChange input,
                        @AuthenticationPrincipal AccountPrincipal actor, HttpServletRequest request) {
        if (!AccountDirectory.fitsBcrypt(input.currentPassword())
                || !AccountDirectory.isNewPasswordValid(input.newPassword())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "新密码至少 12 个字符，且最多 72 个 UTF-8 字节");
        }
        accounts.changeOwnPassword(actor, input.currentPassword(), input.newPassword());
        SecurityContextHolder.clearContext();
        var session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
    }

    record Credentials(@NotBlank @Pattern(regexp = AccountDirectory.USERNAME_PATTERN) String username,
                       @NotBlank @Size(max = 72) String password) {
    }

    record CsrfResponse(String headerName, String token) {
    }

    record PasswordChange(@NotBlank @Size(max = 72) String currentPassword,
                          @NotBlank @Size(min = 12, max = 72) String newPassword) {
    }
}
