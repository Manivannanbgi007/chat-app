package com.chatapp.gateway;

import com.chatapp.gateway.support.AbstractGatewayTest;
import com.chatapp.gateway.support.TestJwt;
import org.junit.jupiter.api.Test;

/** user-service points to a port where nothing listens, so the circuit breaker fallback must answer 503. */
class GatewayFallbackTest extends AbstractGatewayTest {

    @Test
    void returns503InStandardFormatWhenServiceIsDown() {
        client.get().uri("/api/users/me")
                .header("Authorization", "Bearer " + TestJwt.valid("session-1"))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody()
                .jsonPath("$.success").isEqualTo(false)
                .jsonPath("$.message").value(m ->
                        org.junit.jupiter.api.Assertions.assertTrue(m.toString().contains("unavailable")));
    }
}
