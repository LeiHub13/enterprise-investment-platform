package com.enterprise.investment.security;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;

class CorsConfigurationTest {
    @Test
    void onlyExplicitOriginsAreAllowed() {
        var request = new MockHttpServletRequest("GET", "/api/companies");
        var empty = new SecurityConfiguration().corsConfigurationSource("").getCorsConfiguration(request);
        assertNotNull(empty);
        assertNull(empty.checkOrigin("https://untrusted.example"));
        var configured = new SecurityConfiguration().corsConfigurationSource("https://team.example, http://localhost:5173/")
                .getCorsConfiguration(request);
        assertEquals("https://team.example", configured.checkOrigin("https://team.example"));
        assertEquals("http://localhost:5173", configured.checkOrigin("http://localhost:5173"));
        assertNull(configured.checkOrigin("https://untrusted.example"));
        assertFalse(Boolean.TRUE.equals(configured.getAllowCredentials()));
    }

    @Test
    void wildcardAndPathsAreRejected() {
        var security = new SecurityConfiguration();
        assertThrows(IllegalArgumentException.class, () -> security.corsConfigurationSource("*"));
        assertThrows(IllegalArgumentException.class, () -> security.corsConfigurationSource("https://team.example/path"));
    }
}
