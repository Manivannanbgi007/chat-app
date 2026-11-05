package com.chatapp.message.websocket;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SlidingWindowLimiterTest {

    private final SlidingWindowLimiter limiter = new SlidingWindowLimiter();

    @Test
    void allowsUpToTheLimitThenBlocks() {
        assertThat(limiter.allow("s1", 3, 10_000)).isTrue();
        assertThat(limiter.allow("s1", 3, 10_000)).isTrue();
        assertThat(limiter.allow("s1", 3, 10_000)).isTrue();
        assertThat(limiter.allow("s1", 3, 10_000)).isFalse();
    }

    @Test
    void keysAreIndependent() {
        assertThat(limiter.allow("a", 1, 10_000)).isTrue();
        assertThat(limiter.allow("a", 1, 10_000)).isFalse();
        assertThat(limiter.allow("b", 1, 10_000)).isTrue();
    }

    @Test
    void allowsAgainAfterTheWindowPassed() throws InterruptedException {
        assertThat(limiter.allow("w", 1, 100)).isTrue();
        assertThat(limiter.allow("w", 1, 100)).isFalse();
        Thread.sleep(150);
        assertThat(limiter.allow("w", 1, 100)).isTrue();
    }
}
