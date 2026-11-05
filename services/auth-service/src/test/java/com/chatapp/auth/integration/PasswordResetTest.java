package com.chatapp.auth.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import com.chatapp.auth.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

class PasswordResetTest extends AbstractIntegrationTest {

    private String tokenFromLink(String link) {
        return link.substring(link.indexOf("token=") + "token=".length());
    }

    @Test
    void fullResetFlowChangesPasswordAndEndsAllSessions() throws Exception {
        TestUser user = registerVerified();
        String refresh = field(login(user, "d"), "$.data.refreshToken");

        assertStatus(postJson("/api/auth/forgot-password", Map.of("identifier", user.email())), 200);
        String link = emails.lastResetLinkFor(user.email());
        assertThat(link).startsWith("http://localhost:5210/reset-password?token=");
        String token = tokenFromLink(link);

        MvcResult reset = postJson("/api/auth/reset-password", Map.of("token", token, "newPassword", "NewPassw0rd9"));
        assertStatus(reset, 200);

        // old password and old sessions are gone
        assertStatus(login(user, "d"), 401);
        assertStatus(postJson("/api/auth/refresh", Map.of("refreshToken", refresh)), 401);

        // new password works
        MvcResult newLogin = postJson("/api/auth/login", Map.of("identifier", user.email(), "password", "NewPassw0rd9"));
        assertStatus(newLogin, 200);

        // the link works only once
        MvcResult again = postJson("/api/auth/reset-password", Map.of("token", token, "newPassword", "Another1Passw0rd"));
        assertStatus(again, 400);
        assertThat(field(again, "$.data.errorCode")).isEqualTo("INVALID_RESET_TOKEN");
    }

    @Test
    void resetTokenIsStoredOnlyAsHash() throws Exception {
        TestUser user = registerVerified();
        assertStatus(postJson("/api/auth/forgot-password", Map.of("identifier", user.email())), 200);
        String token = tokenFromLink(emails.lastResetLinkFor(user.email()));

        String stored = jdbc.queryForObject("SELECT token_hash FROM password_reset_tokens WHERE user_id = ?", String.class, user.userId());
        assertThat(stored).isNotEqualTo(token).hasSize(64);
    }

    @Test
    void forgotPasswordForUnknownUserLooksTheSameAndSendsNothing() throws Exception {
        String ghost = "ghost-" + UUID.randomUUID() + "@example.com";
        MvcResult result = postJson("/api/auth/forgot-password", Map.of("identifier", ghost));
        assertStatus(result, 200);
        assertThat(emails.emailsSentTo(ghost)).isZero();
    }

    @Test
    void newerResetLinkInvalidatesTheOlderOne() throws Exception {
        TestUser user = registerVerified();
        assertStatus(postJson("/api/auth/forgot-password", Map.of("identifier", user.email())), 200);
        String first = tokenFromLink(emails.lastResetLinkFor(user.email()));
        assertStatus(postJson("/api/auth/forgot-password", Map.of("identifier", user.email())), 200);
        String second = tokenFromLink(emails.lastResetLinkFor(user.email()));

        assertThat(second).isNotEqualTo(first);
        assertStatus(postJson("/api/auth/reset-password", Map.of("token", first, "newPassword", "NewPassw0rd9")), 400);
        assertStatus(postJson("/api/auth/reset-password", Map.of("token", second, "newPassword", "NewPassw0rd9")), 200);
    }

    @Test
    void expiredOrInvalidTokenAndWeakPasswordAreRejected() throws Exception {
        TestUser user = registerVerified();
        assertStatus(postJson("/api/auth/forgot-password", Map.of("identifier", user.email())), 200);
        String token = tokenFromLink(emails.lastResetLinkFor(user.email()));

        assertStatus(postJson("/api/auth/reset-password", Map.of("token", "nonsense", "newPassword", "NewPassw0rd9")), 400);

        MvcResult weak = postJson("/api/auth/reset-password", Map.of("token", token, "newPassword", "weak"));
        assertStatus(weak, 400);
        assertThat(field(weak, "$.data.newPassword")).isNotBlank();

        setTimeFromNow("password_reset_tokens", "expires_at", "user_id", user.userId(), -5);
        assertStatus(postJson("/api/auth/reset-password", Map.of("token", token, "newPassword", "NewPassw0rd9")), 400);
    }

    @Test
    void resetAlsoUnlocksALockedAccount() throws Exception {
        TestUser user = registerVerified();
        for (int i = 0; i < 5; i++) {
            postJson("/api/auth/login", Map.of("identifier", user.email(), "password", "Wrong123456"));
        }
        assertStatus(login(user, "d"), 423);

        assertStatus(postJson("/api/auth/forgot-password", Map.of("identifier", user.email())), 200);
        String token = tokenFromLink(emails.lastResetLinkFor(user.email()));
        assertStatus(postJson("/api/auth/reset-password", Map.of("token", token, "newPassword", "NewPassw0rd9")), 200);

        assertStatus(postJson("/api/auth/login", Map.of("identifier", user.email(), "password", "NewPassw0rd9")), 200);
    }
}
