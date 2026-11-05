package com.chatapp.auth.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.chatapp.auth.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MvcResult;

class LoginAndTokensTest extends AbstractIntegrationTest {

    @Autowired
    private JwtDecoder jwtDecoder;
    @Autowired
    private StringRedisTemplate redis;

    // ---------------------------------------------------------------- login

    @Test
    void verifiedUserCanLoginAndGetsValidTokens() throws Exception {
        TestUser user = registerVerified();

        MvcResult result = login(user, "Test laptop");
        assertStatus(result, 200);

        assertThat(field(result, "$.data.tokenType")).isEqualTo("Bearer");
        assertThat(field(result, "$.data.expiresIn")).isEqualTo("900");
        assertThat(field(result, "$.data.user.id")).isEqualTo(user.userId());
        assertThat(field(result, "$.data.user.username")).isEqualTo(user.username());

        Jwt jwt = jwtDecoder.decode(field(result, "$.data.accessToken"));
        assertThat(jwt.getSubject()).isEqualTo(user.userId());
        assertThat(jwt.getClaimAsString("iss")).isEqualTo("chat-app-auth");
        assertThat(jwt.getClaimAsStringList("roles")).containsExactly("USER");
        assertThat(jwt.getClaimAsString("sid")).isNotBlank();
        assertThat(jwt.getExpiresAt()).isBefore(Instant.now().plusSeconds(901));
    }

    @Test
    void refreshTokenIsStoredOnlyAsHash() throws Exception {
        TestUser user = registerVerified();
        MvcResult result = login(user, "d");
        String refreshToken = field(result, "$.data.refreshToken");

        List<String> hashes = jdbc.queryForList("SELECT token_hash FROM refresh_tokens WHERE user_id = ?", String.class, user.userId());
        assertThat(hashes).hasSize(1);
        assertThat(hashes.get(0)).hasSize(64).isNotEqualTo(refreshToken);
    }

    @Test
    void canLoginWithPhoneNumberToo() throws Exception {
        String phone = "+9197" + (10000000 + (int) (Math.random() * 89999999));
        String suffix = java.util.UUID.randomUUID().toString().substring(0, 8);
        String email = "phone_" + suffix + "@example.com";
        assertStatus(postJson("/api/auth/register", Map.of("username", "phone_" + suffix, "email", email,
                "phone", phone, "password", PASSWORD, "displayName", "Phone user")), 201);
        assertStatus(postJson("/api/auth/verify-otp",
                Map.of("identifier", email, "otp", emails.lastCodeFor(email), "purpose", "EMAIL_VERIFY")), 200);

        MvcResult result = postJson("/api/auth/login", Map.of("identifier", phone, "password", PASSWORD));
        assertStatus(result, 200);
    }

    @Test
    void unverifiedEmailCannotLogin() throws Exception {
        TestUser user = register();

        MvcResult result = login(user, "d");
        assertStatus(result, 403);
        assertThat(field(result, "$.data.errorCode")).isEqualTo("EMAIL_NOT_VERIFIED");
    }

    @Test
    void wrongPasswordAndUnknownUserGiveTheSameAnswer() throws Exception {
        TestUser user = registerVerified();

        MvcResult wrongPassword = postJson("/api/auth/login", Map.of("identifier", user.email(), "password", "Wrong123456"));
        MvcResult unknownUser = postJson("/api/auth/login", Map.of("identifier", "ghost@example.com", "password", "Wrong123456"));

        assertStatus(wrongPassword, 401);
        assertStatus(unknownUser, 401);
        assertThat(field(wrongPassword, "$.message")).isEqualTo(field(unknownUser, "$.message"));
        assertThat(field(wrongPassword, "$.data.errorCode")).isEqualTo("INVALID_CREDENTIALS");
    }

    @Test
    void fiveWrongPasswordsLockTheAccount() throws Exception {
        TestUser user = registerVerified();

        for (int i = 0; i < 5; i++) {
            assertStatus(postJson("/api/auth/login", Map.of("identifier", user.email(), "password", "Wrong123456")), 401);
        }
        // even the correct password is refused while locked
        MvcResult locked = login(user, "d");
        assertStatus(locked, 423);
        assertThat(field(locked, "$.data.errorCode")).isEqualTo("ACCOUNT_LOCKED");

        // lock expires
        setTimeFromNow("auth_users", "locked_until", "id", user.userId(), -1);
        assertStatus(login(user, "d"), 200);
    }

    @Test
    void successfulLoginResetsTheFailureCounter() throws Exception {
        TestUser user = registerVerified();
        for (int i = 0; i < 4; i++) {
            assertStatus(postJson("/api/auth/login", Map.of("identifier", user.email(), "password", "Wrong123456")), 401);
        }
        assertStatus(login(user, "d"), 200);
        // 4 more wrong attempts would lock only if the counter was not reset
        for (int i = 0; i < 4; i++) {
            assertStatus(postJson("/api/auth/login", Map.of("identifier", user.email(), "password", "Wrong123456")), 401);
        }
        assertStatus(login(user, "d"), 200);
    }

    // ------------------------------------------------------- refresh rotation

    @Test
    void refreshRotatesTokensAndOldRefreshTokenStopsWorking() throws Exception {
        TestUser user = registerVerified();
        MvcResult first = login(user, "d");
        String refresh1 = field(first, "$.data.refreshToken");

        MvcResult second = postJson("/api/auth/refresh", Map.of("refreshToken", refresh1));
        assertStatus(second, 200);
        String refresh2 = field(second, "$.data.refreshToken");
        assertThat(refresh2).isNotEqualTo(refresh1);
        assertThat(field(second, "$.data.accessToken")).isNotBlank();
        assertThat(field(second, "$.data.user.id")).isEqualTo(user.userId());

        // new token keeps working (same session)
        assertStatus(postJson("/api/auth/refresh", Map.of("refreshToken", refresh2)), 200);
    }

    @Test
    void reusingAnOldRefreshTokenEndsTheWholeSession() throws Exception {
        TestUser user = registerVerified();
        MvcResult first = login(user, "d");
        String refresh1 = field(first, "$.data.refreshToken");
        String sessionId = jwtDecoder.decode(field(first, "$.data.accessToken")).getClaimAsString("sid");

        MvcResult second = postJson("/api/auth/refresh", Map.of("refreshToken", refresh1));
        String refresh2 = field(second, "$.data.refreshToken");

        // someone replays the old token -> rejected AND the session is killed
        MvcResult replay = postJson("/api/auth/refresh", Map.of("refreshToken", refresh1));
        assertStatus(replay, 401);
        assertThat(field(replay, "$.data.errorCode")).isEqualTo("INVALID_REFRESH_TOKEN");

        // the legitimate newest token no longer works either
        assertStatus(postJson("/api/auth/refresh", Map.of("refreshToken", refresh2)), 401);

        Integer revoked = jdbc.queryForObject("SELECT revoked FROM login_sessions WHERE id = ?", Integer.class, sessionId);
        assertThat(revoked).isEqualTo(1);
        assertThat(redis.hasKey("auth:revoked:" + sessionId)).isTrue();
        Integer audit = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE user_id = ? AND action = 'TOKEN_REUSE_DETECTED'", Integer.class, user.userId());
        assertThat(audit).isEqualTo(1);
    }

    @Test
    void unknownOrExpiredRefreshTokenIsRejected() throws Exception {
        assertStatus(postJson("/api/auth/refresh", Map.of("refreshToken", "does-not-exist")), 401);

        TestUser user = registerVerified();
        String refresh = field(login(user, "d"), "$.data.refreshToken");
        setTimeFromNow("refresh_tokens", "expires_at", "user_id", user.userId(), -5);
        assertStatus(postJson("/api/auth/refresh", Map.of("refreshToken", refresh)), 401);
    }

    // ----------------------------------------------------------------- logout

    @Test
    void logoutEndsTheSessionAndRevokesItsAccessToken() throws Exception {
        TestUser user = registerVerified();
        MvcResult loggedIn = login(user, "d");
        String refresh = field(loggedIn, "$.data.refreshToken");
        String sessionId = jwtDecoder.decode(field(loggedIn, "$.data.accessToken")).getClaimAsString("sid");

        assertStatus(postJson("/api/auth/logout", Map.of("refreshToken", refresh)), 200);

        assertStatus(postJson("/api/auth/refresh", Map.of("refreshToken", refresh)), 401);
        assertThat(redis.hasKey("auth:revoked:" + sessionId)).isTrue();

        // logging out again, or with a garbage token, is harmless
        assertStatus(postJson("/api/auth/logout", Map.of("refreshToken", refresh)), 200);
        assertStatus(postJson("/api/auth/logout", Map.of("refreshToken", "garbage")), 200);
    }

    // --------------------------------------------------------------- sessions

    @Test
    void sessionListShowsDevicesAndMarksTheCurrentOne() throws Exception {
        TestUser user = registerVerified();
        MvcResult laptop = login(user, "Laptop");
        login(user, "Phone");
        String token = field(laptop, "$.data.accessToken");

        MvcResult list = mockMvc.perform(get("/api/auth/sessions").header("Authorization", "Bearer " + token)).andReturn();
        assertStatus(list, 200);
        List<String> names = com.jayway.jsonpath.JsonPath.read(list.getResponse().getContentAsString(), "$.data[*].deviceName");
        List<Boolean> current = com.jayway.jsonpath.JsonPath.read(list.getResponse().getContentAsString(), "$.data[?(@.current==true)].current");
        assertThat(names).containsExactlyInAnyOrder("Laptop", "Phone");
        assertThat(current).hasSize(1);
        List<String> currentDevices = com.jayway.jsonpath.JsonPath.read(
                list.getResponse().getContentAsString(), "$.data[?(@.current==true)].deviceName");
        assertThat(currentDevices).containsExactly("Laptop");
    }

    @Test
    void userCanEndAnotherDeviceButNotSomeoneElsesSession() throws Exception {
        TestUser alice = registerVerified();
        TestUser bob = registerVerified();
        MvcResult aliceLaptop = login(alice, "Laptop");
        MvcResult alicePhone = login(alice, "Phone");
        MvcResult bobLaptop = login(bob, "Laptop");

        String alicePhoneSession = jwtDecoder.decode(field(alicePhone, "$.data.accessToken")).getClaimAsString("sid");
        String bobSession = jwtDecoder.decode(field(bobLaptop, "$.data.accessToken")).getClaimAsString("sid");
        String aliceToken = field(aliceLaptop, "$.data.accessToken");

        // Alice cannot end Bob's session (404, as if it did not exist)
        MvcResult foreign = mockMvc.perform(delete("/api/auth/sessions/" + bobSession)
                .header("Authorization", "Bearer " + aliceToken)).andReturn();
        assertStatus(foreign, 404);

        // Alice ends her phone session; its refresh token dies
        MvcResult own = mockMvc.perform(delete("/api/auth/sessions/" + alicePhoneSession)
                .header("Authorization", "Bearer " + aliceToken)).andReturn();
        assertStatus(own, 200);
        assertStatus(postJson("/api/auth/refresh", Map.of("refreshToken", field(alicePhone, "$.data.refreshToken"))), 401);
        assertThat(redis.hasKey("auth:revoked:" + alicePhoneSession)).isTrue();
    }
}
