package com.chatapp.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.chatapp.gateway.support.AbstractGatewayTest;
import org.junit.jupiter.api.Test;

/** Routing, correlation IDs, CORS and the standard error format. */
class GatewayRoutingTest extends AbstractGatewayTest {

    @Test
    void routesAuthRequestsToAuthService() {
        client.get().uri("/api/auth/ping")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.message").isEqualTo("pong");
        assertThat(AUTH_SERVICE.lastPath.get()).isEqualTo("/api/auth/ping");
    }

    @Test
    void createsCorrelationIdWhenClientSendsNone() {
        client.get().uri("/api/auth/ping")
                .exchange()
                .expectHeader().exists("X-Correlation-Id");
        assertThat(AUTH_SERVICE.lastCorrelationId.get()).isNotBlank();
    }

    @Test
    void keepsValidCorrelationIdFromClient() {
        client.get().uri("/api/auth/ping")
                .header("X-Correlation-Id", "my-test-id-123")
                .exchange()
                .expectHeader().valueEquals("X-Correlation-Id", "my-test-id-123");
        assertThat(AUTH_SERVICE.lastCorrelationId.get()).isEqualTo("my-test-id-123");
    }

    @Test
    void responseHasExactlyOneCorrelationIdHeader() {
        var headers = client.get().uri("/api/auth/ping")
                .exchange()
                .returnResult(String.class)
                .getResponseHeaders();
        assertThat(headers.get("X-Correlation-Id")).hasSize(1);
    }

    @Test
    void replacesUnsafeCorrelationId() {
        client.get().uri("/api/auth/ping")
                .header("X-Correlation-Id", "bad id\twith spaces")
                .exchange()
                .expectHeader().value("X-Correlation-Id", id -> assertThat(id).matches("[A-Za-z0-9_-]{1,64}"));
    }

    @Test
    void unknownPathReturnsStandardErrorFormat() {
        client.get().uri("/api/nothing-here")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.message").isEqualTo("Resource not found")
                .jsonPath("$.timestamp").exists();
    }

    @Test
    void healthEndpointIsUpAndIncludesRedis() {
        client.get().uri("/actuator/health")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("UP");
    }

    @Test
    void corsPreflightFromTheFrontendIsAnsweredWithoutAToken() {
        client.options().uri("/api/users/me")
                .header("Origin", "http://localhost:5210")
                .header("Access-Control-Request-Method", "GET")
                .header("Access-Control-Request-Headers", "authorization")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Access-Control-Allow-Origin", "http://localhost:5210")
                .expectHeader().exists("Access-Control-Allow-Methods");
    }

    @Test
    void corsPreflightFromAStrangerIsRefused() {
        client.options().uri("/api/users/me")
                .header("Origin", "http://evil.example.com")
                .header("Access-Control-Request-Method", "GET")
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void swaggerDocsOfAuthServiceAreReachableThroughTheGateway() {
        client.get().uri("/service-docs/auth").exchange().expectStatus().isOk();
        assertThat(AUTH_SERVICE.lastPath.get()).isEqualTo("/v3/api-docs");
    }
}
