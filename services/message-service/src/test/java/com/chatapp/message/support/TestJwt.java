package com.chatapp.message.support;

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

/** Builds access tokens like auth-service does. */
public final class TestJwt {

    public static final String SECRET = "test-only-jwt-secret-that-is-long-enough-123456";

    private TestJwt() {
    }

    public static String create(UUID userId, UUID sessionId, Instant expiresAt) {
        return create(SECRET, "chat-app-auth", userId, sessionId, expiresAt);
    }

    public static String create(String secret, String issuer, UUID userId, UUID sessionId, Instant expiresAt) {
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(
                new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(userId.toString())
                .issuedAt(expiresAt.minusSeconds(900))
                .expiresAt(expiresAt)
                .claim("sid", sessionId.toString())
                .claim("roles", List.of("USER"))
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    public static String valid(UUID userId, UUID sessionId) {
        return create(userId, sessionId, Instant.now().plusSeconds(600));
    }
}
