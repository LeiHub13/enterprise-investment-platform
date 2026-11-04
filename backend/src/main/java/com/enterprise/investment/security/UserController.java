package com.enterprise.investment.security;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/users")
class UserController {
    private final AccountDirectory accounts;

    UserController(AccountDirectory accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'INVESTMENT_MANAGER')")
    List<AccountPrincipal> list(@AuthenticationPrincipal AccountPrincipal actor) {
        return accounts.listEnabled("ADMIN".equals(actor.role()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    AccountPrincipal create(@Valid @RequestBody NewAccount input,
                            @AuthenticationPrincipal AccountPrincipal actor) {
        if (!AccountDirectory.isNewPasswordValid(input.password())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "密码至少 12 个字符，且最多 72 个 UTF-8 字节");
        }
        return accounts.createAs(actor, input.username(), input.password(), input.role());
    }

    record NewAccount(@NotBlank @Pattern(regexp = AccountDirectory.USERNAME_PATTERN) String username,
                      @NotBlank @Size(min = 12, max = 72) String password,
                      @NotBlank @Pattern(regexp = AccountDirectory.ROLE_PATTERN) String role) {
    }
}
