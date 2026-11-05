package com.chatapp.notification;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Creates signed access tokens the same way auth-service does, for tests. */
public final class TestJwt {

    public static final String SECRET = "test-secret-test-secret-test-secret-123456";
    public static final String ISSUER = "chat-app-auth";
    /** Throw-away value for the shared service-to-service token (X-Internal-Token), tests only. */
    public static final String INTERNAL_TOKEN = "test-internal-token-test-internal-1234";

    private TestJwt() {
    }

    public static String token(UUID userId) {
        return token(userId, SECRET, ISSUER, Duration.ofMinutes(15));
    }

    public static String token(UUID userId, String secret, String issuer, Duration validFor) {
        SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        Instant now = Instant.now();
        // A negative validFor makes an already expired token: it was issued a minute before it expired.
        Instant expiresAt = now.plus(validFor);
        Instant issuedAt = validFor.isNegative() ? expiresAt.minusSeconds(60) : now;
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(userId.toString())
                .claim("sid", UUID.randomUUID().toString())
                .claim("roles", List.of("USER"))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }
}
