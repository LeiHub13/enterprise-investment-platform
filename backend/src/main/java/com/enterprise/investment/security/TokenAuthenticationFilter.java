package com.enterprise.investment.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

class TokenAuthenticationFilter extends OncePerRequestFilter {
    private final TokenService tokens;
    private final AccountDirectory accounts;

    TokenAuthenticationFilter(TokenService tokens, AccountDirectory accounts) {
        this.tokens = tokens;
        this.accounts = accounts;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (SecurityContextHolder.getContext().getAuthentication() == null) {
            String raw = TokenService.resolveBearer(request.getHeader(HttpHeaders.AUTHORIZATION));
            if (raw != null) {
                tokens.authenticate(raw)
                        .flatMap(accounts::loadPrincipal)
                        .ifPresent(principal -> SecurityContextHolder.getContext().setAuthentication(
                                UsernamePasswordAuthenticationToken.authenticated(
                                        principal, null, authorities(principal))));
            }
        }
        chain.doFilter(request, response);
    }

    private static List<GrantedAuthority> authorities(AccountPrincipal principal) {
        List<GrantedAuthority> authorities = new ArrayList<>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + principal.role()));
        principal.permissions().forEach(code -> authorities.add(new SimpleGrantedAuthority(code)));
        return authorities;
    }
}
