package com.enterprise.investment.security;

import com.enterprise.investment.mapper.TokenMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.time.LocalDateTime;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TokenServiceTest {
    private final TokenMapper mapper = mock(TokenMapper.class);
    private final TokenService service = new TokenService(mapper, 12);

    @Test
    void issuesHashedTokenWithExpiry() {
        when(mapper.insert(any(Long.class), any(), any(), any(), any())).thenReturn(1);

        TokenService.IssuedToken issued = service.issue(7, "127.0.0.1", "JUnit");

        assertNotNull(issued.token());
        assertFalse(issued.token().isBlank());
        assertTrue(issued.expiresAt().isAfter(LocalDateTime.now().plusHours(11)));
        ArgumentCaptor<String> hash = ArgumentCaptor.forClass(String.class);
        verify(mapper).insert(eq(7L), hash.capture(), any(), eq("127.0.0.1"), eq("JUnit"));
        assertEquals(64, hash.getValue().length());
        assertEquals(TokenService.hash(issued.token()), hash.getValue());
    }

    @Test
    void authenticatesOnlyValidTokenRecord() {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("user_id", 7L);
        when(mapper.findValidByHash(eq(TokenService.hash("raw-token")), any())).thenReturn(row);

        assertEquals(Optional.of(7L), service.authenticate("raw-token"));
        assertEquals(Optional.empty(), service.authenticate("other-token"));
        assertEquals(Optional.empty(), service.authenticate(""));
    }

    @Test
    void revokesByHash() {
        service.revoke("raw-token");

        verify(mapper).revokeByHash(TokenService.hash("raw-token"));
    }

    @Test
    void readsBearerHeader() {
        assertEquals("abc", TokenService.resolveBearer("Bearer abc"));
        assertEquals(null, TokenService.resolveBearer(null));
        assertEquals(null, TokenService.resolveBearer("Basic abc"));
        assertEquals(null, TokenService.resolveBearer("Bearer   "));
    }

    @Test
    void configuredJwtIsSignedRevocableAndCompatibleWithExistingTokens() {
        String secret = Base64.getEncoder().encodeToString("01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8));
        TokenService jwtService = new TokenService(mapper, 12, secret);
        TokenService.IssuedToken issued = jwtService.issue(7, "127.0.0.1", "JUnit");
        String[] parts = issued.token().split("\\.");
        assertEquals(3, parts.length);
        when(mapper.findValidByHash(eq(TokenService.hash(issued.token())), any())).thenReturn(Map.of("user_id", 7L));
        assertEquals(Optional.of(7L), jwtService.authenticate(issued.token()));

        String changedSignature = (parts[2].charAt(0) == 'A' ? "B" : "A") + parts[2].substring(1);
        String tampered = parts[0] + "." + parts[1] + "." + changedSignature;
        when(mapper.findValidByHash(eq(TokenService.hash(tampered)), any())).thenReturn(Map.of("user_id", 7L));
        assertEquals(Optional.empty(), jwtService.authenticate(tampered));

        when(mapper.findValidByHash(eq(TokenService.hash("legacy-token")), any())).thenReturn(Map.of("user_id", 7L));
        assertEquals(Optional.of(7L), jwtService.authenticate("legacy-token"));
        jwtService.revoke(issued.token());
        verify(mapper).revokeByHash(TokenService.hash(issued.token()));
    }

    @Test
    void rejectsShortJwtSecrets() {
        String shortSecret = Base64.getEncoder().encodeToString("short".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> new TokenService(mapper, 12, shortSecret));
    }

    @Test
    void rejectsExpiredJwtEvenWhenItsHashExists() {
        byte[] key = "01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8);
        TokenService jwtService = new TokenService(mapper, 12, Base64.getEncoder().encodeToString(key));
        var encoder = new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(key));
        var claims = JwtClaimsSet.builder().issuer("https://enterprise-investment.local").subject("7")
                .issuedAt(Instant.now().minusSeconds(3600)).expiresAt(Instant.now().minusSeconds(120)).id("expired-test").build();
        String raw = encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
        when(mapper.findValidByHash(eq(TokenService.hash(raw)), any())).thenReturn(Map.of("user_id", 7L));
        assertEquals(Optional.empty(), jwtService.authenticate(raw));
    }
}
