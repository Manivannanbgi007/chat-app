package com.chatapp.presence.support;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Builds access tokens like auth-service does (user id in "sub", device = login session in "sid"). */
public final class TestJwt {

    public static final String SECRET = "test-only-jwt-secret-that-is-long-enough-123456";
    public static final String INTERNAL_TOKEN = "test-only-internal-token-0123456789";
    public static final String ISSUER = "chat-app-auth";

    private TestJwt() {
    }

    /** A token for a user on one device. */
    public static String token(UUID userId, UUID sessionId) {
        return build(userId.toString(), sessionId == null ? null : sessionId.toString());
    }

    /** A token without the "sid" claim (must be refused). */
    public static String tokenWithoutDevice(UUID userId) {
        return build(userId.toString(), null);
    }

    public static String build(String subject, String sessionId) {
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(
                new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        Instant now = Instant.now();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(subject)
                .issuedAt(now.minusSeconds(60))
                .expiresAt(now.plusSeconds(600))
                .claim("roles", List.of("USER"));
        if (sessionId != null) {
            claims.claim("sid", sessionId);
        }
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims.build()))
                .getTokenValue();
    }
}
