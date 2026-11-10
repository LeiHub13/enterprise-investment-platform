package com.enterprise.investment.security;

import com.enterprise.investment.mapper.TokenMapper;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.proc.SecurityContext;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import javax.crypto.spec.SecretKeySpec;

@Service
class TokenService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String JWT_ISSUER = "https://enterprise-investment.local";

    private final TokenMapper tokens;
    private final long ttlHours;
    private final JwtEncoder jwtEncoder;
    private final JwtDecoder jwtDecoder;

    @Autowired
    TokenService(TokenMapper tokens, @Value("${app.auth.token-ttl-hours:12}") long ttlHours,
                 @Value("${app.auth.jwt-secret:}") String encodedSecret) {
        this.tokens = tokens;
        this.ttlHours = ttlHours;
        if (ttlHours < 1) throw new IllegalArgumentException("Token TTL must be positive");
        if (encodedSecret == null || encodedSecret.isBlank()) {
            this.jwtEncoder = null;
            this.jwtDecoder = null;
        } else {
            byte[] secret;
            try {
                secret = Base64.getDecoder().decode(encodedSecret.trim());
            } catch (IllegalArgumentException error) {
                throw new IllegalArgumentException("APP_JWT_SECRET must be base64-encoded", error);
            }
            if (secret.length < 32) throw new IllegalArgumentException("APP_JWT_SECRET must contain at least 32 bytes");
            this.jwtEncoder = new NimbusJwtEncoder(new ImmutableSecret<SecurityContext>(secret));
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(new SecretKeySpec(secret, "HmacSHA256"))
                    .macAlgorithm(MacAlgorithm.HS256).build();
            decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(JWT_ISSUER));
            this.jwtDecoder = decoder;
        }
    }

    TokenService(TokenMapper tokens, long ttlHours) {
        this(tokens, ttlHours, "");
    }

    IssuedToken issue(long userId, String ip, String userAgent) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String identifier = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        String raw = identifier;
        if (jwtEncoder != null) {
            Instant now = Instant.now();
            JwtClaimsSet claims = JwtClaimsSet.builder().issuer(JWT_ISSUER).subject(Long.toString(userId))
                    .issuedAt(now).expiresAt(now.plus(ttlHours, ChronoUnit.HOURS)).id(identifier).build();
            raw = jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).type("JWT").build(), claims))
                    .getTokenValue();
        }
        LocalDateTime expiresAt = LocalDateTime.now().plusHours(ttlHours);
        tokens.deleteExpired(LocalDateTime.now());
        tokens.insert(userId, hash(raw), expiresAt, truncate(ip, 64), truncate(userAgent, 255));
        return new IssuedToken(raw, expiresAt);
    }

    Optional<Long> authenticate(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        Map<String, Object> row = tokens.findValidByHash(hash(raw), LocalDateTime.now());
        if (row == null || !(row.get("user_id") instanceof Number userId)) {
            return Optional.empty();
        }
        if (raw.indexOf('.') >= 0) {
            if (jwtDecoder == null) return Optional.empty();
            try {
                Jwt jwt = jwtDecoder.decode(raw);
                if (!Long.toString(userId.longValue()).equals(jwt.getSubject())) return Optional.empty();
            } catch (JwtException error) {
                return Optional.empty();
            }
        }
        return Optional.of(userId.longValue());
    }

    void revoke(String raw) {
        if (raw != null && !raw.isBlank()) {
            tokens.revokeByHash(hash(raw));
        }
    }

    void revokeAll(long userId) {
        tokens.revokeAllByUser(userId);
    }

    static String resolveBearer(String header) {
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            return null;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        return token.isEmpty() ? null : token;
    }

    static String hash(String raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    record IssuedToken(String token, LocalDateTime expiresAt) {
    }
}
