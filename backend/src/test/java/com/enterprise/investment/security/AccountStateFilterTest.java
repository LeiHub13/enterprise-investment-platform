package com.enterprise.investment.security;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
class AccountStateFilterTest {
    private final TestDirectory accounts = new TestDirectory();
    private final AccountStateFilter filter = new AccountStateFilter(accounts);
    private final AccountPrincipal principal = new AccountPrincipal(7, "officer", "INVESTMENT_OFFICER", "version");

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void invalidatedAccountLosesSessionBeforeRequestIsProcessed() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
        accounts.current = false;
        var request = new MockHttpServletRequest();
        var session = (MockHttpSession) request.getSession();
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertEquals(401, response.getStatus());
        assertTrue(session.isInvalid());
        assertNull(chain.getRequest());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void currentAccountContinuesThroughFilter() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
        accounts.current = true;
        var request = new MockHttpServletRequest();
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertSame(request, chain.getRequest());
        assertEquals(200, response.getStatus());
    }

    private static class TestDirectory extends AccountDirectory {
        boolean current;

        TestDirectory() {
            super(new JdbcTemplate(), new BCryptPasswordEncoder());
        }

        @Override
        boolean isCurrent(AccountPrincipal actor) {
            return current;
        }
    }
}
