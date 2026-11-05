package com.chatapp.auth.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import javax.crypto.spec.SecretKeySpec;

import com.chatapp.auth.support.AbstractIntegrationTest;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MvcResult;

/** Protected endpoints must reject anything that is not a correct, unexpired token from us. */
class SecurityRulesTest extends AbstractIntegrationTest {

    private static final String REAL_SECRET = "test-only-jwt-secret-that-is-long-enough-123456";

    private String token(String secret, String issuer, Instant expiresAt) {
        NimbusJwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(
                new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256")));
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(java.util.UUID.randomUUID().toString())
                .issuedAt(expiresAt.minusSeconds(900))
                .expiresAt(expiresAt)
                .claim("sid", java.util.UUID.randomUUID().toString())
                .claim("roles", List.of("USER"))
                .build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private MvcResult getSessions(String bearer) throws Exception {
        var request = get("/api/auth/sessions");
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return mockMvc.perform(request).andReturn();
    }

    @Test
    void protectedEndpointWithoutTokenIs401InStandardFormat() throws Exception {
        MvcResult result = getSessions(null);
        assertStatus(result, 401);
        assertThat(field(result, "$.success")).isEqualTo("false");
        assertThat(field(result, "$.message")).isNotBlank();
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() throws Exception {
        String forged = token("another-secret-another-secret-another-123", "chat-app-auth", Instant.now().plusSeconds(600));
        assertStatus(getSessions(forged), 401);
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        // Spring allows 60 seconds of clock difference, so use a token that expired 5 minutes ago
        String expired = token(REAL_SECRET, "chat-app-auth", Instant.now().minusSeconds(300));
        assertStatus(getSessions(expired), 401);
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() throws Exception {
        String other = token(REAL_SECRET, "someone-else", Instant.now().plusSeconds(600));
        assertStatus(getSessions(other), 401);
    }

    @Test
    void garbageTokenIsRejected() throws Exception {
        assertStatus(getSessions("not.a.jwt"), 401);
    }

    @Test
    void validTokenIsAccepted() throws Exception {
        String valid = token(REAL_SECRET, "chat-app-auth", Instant.now().plusSeconds(600));
        assertStatus(getSessions(valid), 200);
    }

    @Test
    void publicEndpointsNeedNoToken() throws Exception {
        assertStatus(mockMvc.perform(get("/api/auth/ping")).andReturn(), 200);
        assertStatus(mockMvc.perform(get("/actuator/health")).andReturn(), 200);
    }

    @Test
    void unknownPathsAreNotPubliclyReadable() throws Exception {
        assertStatus(mockMvc.perform(get("/api/auth/not-a-real-endpoint")).andReturn(), 401);
    }
}
