package com.chatapp.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import com.chatapp.gateway.support.AbstractGatewayTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Own application context with a tiny limit (burst of 3). Each test uses a different client IP
 * (X-Forwarded-For, as nginx would send it), so the tests never share a counter.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "chatapp.ratelimit.auth.replenish-rate=1",
                "chatapp.ratelimit.auth.burst-capacity=3"})
class RateLimitTest extends AbstractGatewayTest {

    private List<Integer> fireRequests(String clientIp, int count) {
        List<Integer> statuses = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            statuses.add(client.get().uri("/api/auth/ping")
                    .header("X-Forwarded-For", clientIp)
                    .exchange()
                    .returnResult(String.class)
                    .getStatus().value());
        }
        return statuses;
    }

    @Test
    void tooManyRequestsFromOneIpGet429() {
        List<Integer> statuses = fireRequests("10.20.0.1", 8);

        assertThat(statuses.get(0)).isEqualTo(200);
        assertThat(statuses).contains(429);
    }

    @Test
    void anotherIpIsNotAffected() {
        fireRequests("10.20.0.2", 8);   // exhaust this IP

        assertThat(fireRequests("10.20.0.3", 1)).containsExactly(200);
    }

    @Test
    void rateLimitHeadersTellTheClientWhatIsLeft() {
        var headers = client.get().uri("/api/auth/ping")
                .header("X-Forwarded-For", "10.20.0.4")
                .exchange()
                .returnResult(String.class)
                .getResponseHeaders();

        assertThat(headers.getFirst("X-RateLimit-Burst-Capacity")).isEqualTo("3");
        assertThat(headers.getFirst("X-RateLimit-Remaining")).isNotNull();
    }
}
