package com.chatapp.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.chatapp.gateway.support.AbstractGatewayTest;
import com.chatapp.gateway.support.TestJwt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Found by the Phase 9 load test: the circuit breaker library silently limits every route to 25 requests at the same
 * time (a "bulkhead" with the library default) and answers 503 "Bulkhead is full" to the rest. A busy chat sends
 * many more requests at the same time than that, so the limit has to be much higher.
 */
class ConcurrencyTest extends AbstractGatewayTest {

    @AfterEach
    void speedUpAgain() {
        MESSAGE_SERVICE.delayMillis = 0;
    }

    @Test
    void sixtyRequestsAtTheSameTimeAreAllServed() throws Exception {
        MESSAGE_SERVICE.delayMillis = 400;   // a service that needs a moment, so the requests really overlap
        int requests = 60;
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        try {
            List<Callable<Integer>> calls = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                // every request from a different user, so the per-user rate limit is not what we test here
                String token = TestJwt.valid("session-" + i);
                calls.add(() -> client.get().uri("/api/messages/sync?since=2026-01-01T00:00:00Z")
                        .header("Authorization", "Bearer " + token)
                        .exchange()
                        .returnResult(String.class)
                        .getStatus().value());
            }
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> future : pool.invokeAll(calls)) {
                statuses.add(future.get());
            }
            assertThat(statuses).as("statuses of %d parallel requests", requests).containsOnly(200);
        } finally {
            pool.shutdownNow();
        }
    }
}
