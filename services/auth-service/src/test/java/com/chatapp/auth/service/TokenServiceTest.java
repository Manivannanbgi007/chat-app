package com.chatapp.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import com.chatapp.auth.config.AuthProperties;
import com.chatapp.auth.config.JwtConfig;
import com.chatapp.auth.entity.AuthUser;
import com.chatapp.auth.enums.Role;
import com.chatapp.auth.util.HashUtil;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Plain unit test: no Spring, no database. */
class TokenServiceTest {

    private static final String SECRET = "unit-test-secret-unit-test-secret-1234";

    private final AuthProperties properties = new AuthProperties(
            new AuthProperties.Security(SECRET, 15, 7, 5, 15),
            new AuthProperties.Otp(10, 5, 5, 60),
            new AuthProperties.PasswordReset(30, 5),
            new AuthProperties.Mail("no-reply@test"),
            "http://localhost:5210");

    private final JwtConfig jwtConfig = new JwtConfig();
    private final SecretKey key = new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    private final TokenService tokenService = new TokenService(jwtConfig.jwtEncoder(key), properties);
    private final JwtDecoder decoder = jwtConfig.jwtDecoder(key);

    @Test
    void accessTokenCarriesUserSessionAndRole() {
        AuthUser user = new AuthUser();
        user.setId(UUID.randomUUID());
        user.setRole(Role.USER);
        UUID sessionId = UUID.randomUUID();

        Jwt jwt = decoder.decode(tokenService.createAccessToken(user, sessionId));

        assertThat(jwt.getSubject()).isEqualTo(user.getId().toString());
        assertThat(jwt.getClaimAsString("sid")).isEqualTo(sessionId.toString());
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("USER");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("chat-app-auth");
        assertThat(jwt.getExpiresAt()).isAfter(jwt.getIssuedAt());
    }

    @Test
    void accessTokenLivesFifteenMinutes() {
        assertThat(tokenService.accessTokenSeconds()).isEqualTo(900);
    }

    @Test
    void opaqueTokensAreLongAndUnique() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 1000; i++) {
            String token = tokenService.newOpaqueToken();
            assertThat(token).hasSizeGreaterThanOrEqualTo(43);
            assertThat(seen.add(token)).isTrue();
        }
    }

    @Test
    void shortJwtSecretIsRefusedAtStartup() {
        AuthProperties weak = new AuthProperties(
                new AuthProperties.Security("too-short", 15, 7, 5, 15), null, null, null, null);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, () -> jwtConfig.jwtSecretKey(weak));
    }

    @Test
    void hashIsDeterministicHexAndHidesTheInput() {
        String hash = HashUtil.sha256Hex("secret-value");
        assertThat(hash).hasSize(64).matches("[0-9a-f]+").isEqualTo(HashUtil.sha256Hex("secret-value"));
        assertThat(hash).doesNotContain("secret");
        assertThat(HashUtil.sha256Hex("other")).isNotEqualTo(hash);
    }
}
