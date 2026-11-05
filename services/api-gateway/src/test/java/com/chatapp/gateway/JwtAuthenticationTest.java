package com.chatapp.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.chatapp.gateway.support.AbstractGatewayTest;
import com.chatapp.gateway.support.TestJwt;
import org.junit.jupiter.api.Test;

/** The gateway must reject every request that has no acceptable token (except the public endpoints). */
class JwtAuthenticationTest extends AbstractGatewayTest {

    private static final String PROTECTED_PATH = "/api/auth/sessions";   // routed to the fake auth-service

    @Test
    void publicEndpointsNeedNoToken() {
        for (String path : new String[]{"/api/auth/ping"}) {
            client.get().uri(path).exchange().expectStatus().isOk();
        }
        client.post().uri("/api/auth/login").exchange().expectStatus().isOk();   // reaches the fake service
        client.post().uri("/api/auth/register").exchange().expectStatus().isOk();
        client.post().uri("/api/auth/refresh").exchange().expectStatus().isOk();
    }

    @Test
    void protectedPathWithoutTokenIs401InStandardFormat() {
        client.get().uri(PROTECTED_PATH)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.message").value(m -> assertThat(m.toString()).contains("Authentication"));
    }

    @Test
    void otherUsersServiceRoutesAreProtectedToo() {
        client.get().uri("/api/users/me").exchange().expectStatus().isUnauthorized();
        client.get().uri("/api/contacts").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void malformedAuthorizationHeadersAreRejected() {
        client.get().uri(PROTECTED_PATH).header("Authorization", "Basic abc").exchange().expectStatus().isUnauthorized();
        client.get().uri(PROTECTED_PATH).header("Authorization", "Bearer ").exchange().expectStatus().isUnauthorized();
        client.get().uri(PROTECTED_PATH).header("Authorization", "Bearer not.a.jwt").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void tokenWithWrongSignatureIsRejected() {
        String forged = TestJwt.create(TestJwt.OTHER_SECRET, "chat-app-auth", "s1", Instant.now().plusSeconds(600));
        client.get().uri(PROTECTED_PATH).header("Authorization", "Bearer " + forged).exchange().expectStatus().isUnauthorized();
    }

    @Test
    void expiredTokenIsRejected() {
        // Spring allows 60 seconds of clock difference, so use one that expired 5 minutes ago
        String expired = TestJwt.create(TestJwt.REAL_SECRET, "chat-app-auth", "s1", Instant.now().minusSeconds(300));
        client.get().uri(PROTECTED_PATH).header("Authorization", "Bearer " + expired).exchange().expectStatus().isUnauthorized();
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() {
        String other = TestJwt.create(TestJwt.REAL_SECRET, "someone-else", "s1", Instant.now().plusSeconds(600));
        client.get().uri(PROTECTED_PATH).header("Authorization", "Bearer " + other).exchange().expectStatus().isUnauthorized();
    }

    @Test
    void tokenWithoutSessionIdIsRejected() {
        String noSession = TestJwt.create(TestJwt.REAL_SECRET, "chat-app-auth", null, Instant.now().plusSeconds(600));
        client.get().uri(PROTECTED_PATH).header("Authorization", "Bearer " + noSession).exchange().expectStatus().isUnauthorized();
    }

    @Test
    void tokenWithoutSubjectIsRejected() {
        // Found by security-check.py (Phase 9): this used to end in a 500 inside the per-user rate limiter
        String noSubject = TestJwt.withoutSubject("s1");
        client.get().uri(PROTECTED_PATH).header("Authorization", "Bearer " + noSubject).exchange().expectStatus().isUnauthorized();
    }

    @Test
    void validTokenIsForwardedUnchangedToTheService() {
        String token = TestJwt.valid(UUID.randomUUID().toString());
        client.get().uri(PROTECTED_PATH)
                .header("Authorization", "Bearer " + token)
                .exchange()
                .expectStatus().isOk();
        assertThat(AUTH_SERVICE.lastAuthorization.get()).isEqualTo("Bearer " + token);
    }

    @Test
    void tokenOfARevokedSessionIsRejected() {
        String sessionId = UUID.randomUUID().toString();
        String token = TestJwt.valid(sessionId);

        client.get().uri(PROTECTED_PATH).header("Authorization", "Bearer " + token).exchange().expectStatus().isOk();

        // auth-service writes this key when the user logs out or ends the session
        redis.opsForValue().set("auth:revoked:" + sessionId, "1", Duration.ofMinutes(5)).block();

        client.get().uri(PROTECTED_PATH).header("Authorization", "Bearer " + token).exchange().expectStatus().isUnauthorized();
        // a token of another session is not affected
        client.get().uri(PROTECTED_PATH)
                .header("Authorization", "Bearer " + TestJwt.valid(UUID.randomUUID().toString()))
                .exchange().expectStatus().isOk();
    }
}
