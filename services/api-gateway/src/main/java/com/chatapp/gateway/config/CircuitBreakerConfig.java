package com.chatapp.gateway.config;

import java.time.Duration;

import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4JCircuitBreakerFactory;
import org.springframework.cloud.circuitbreaker.resilience4j.ReactiveResilience4jBulkheadProvider;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4JConfigBuilder;
import org.springframework.cloud.circuitbreaker.resilience4j.Resilience4jBulkheadConfigurationBuilder;
import org.springframework.cloud.client.circuitbreaker.Customizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Circuit breaker defaults. Two library defaults are too small for a chat and are set here:
 *  - the time limit (1 second),
 *  - the "bulkhead": at most 25 requests at the same time per route, the rest get 503 "Bulkhead is full".
 *    The Phase 9 load test hit that limit with only 50 chatting pairs.
 */
@Configuration
public class CircuitBreakerConfig {

    /** The breaker of the media route (file uploads of up to 100 MB). Every other breaker uses the default limit. */
    public static final String MEDIA_BREAKER = "mediaBreaker";

    @Bean
    public Customizer<ReactiveResilience4JCircuitBreakerFactory> defaultCircuitBreaker(
            @Value("${chatapp.gateway.timeout-seconds:30}") long timeoutSeconds,
            @Value("${chatapp.gateway.media-timeout-seconds:150}") long mediaTimeoutSeconds) {
        return factory -> {
            factory.configureDefault(id -> breaker(id, timeoutSeconds));
            // uploads take longer than normal requests: the media breaker gets a longer time limit
            factory.configure(builder -> builder
                    .circuitBreakerConfig(io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.ofDefaults())
                    .timeLimiterConfig(TimeLimiterConfig.custom()
                            .timeoutDuration(Duration.ofSeconds(mediaTimeoutSeconds))
                            .build()), MEDIA_BREAKER);
        };
    }

    /**
     * How many requests one route may have in progress at the same time (also the limit that protects a slow service
     * from being flooded). A request that finds no free place waits a moment, then gets 503.
     */
    @Bean
    public Customizer<ReactiveResilience4jBulkheadProvider> defaultBulkhead(
            @Value("${chatapp.gateway.max-concurrent-calls:500}") int maxConcurrentCalls,
            @Value("${chatapp.gateway.bulkhead-wait-ms:1000}") long waitMillis) {
        return provider -> provider.configureDefault(id -> new Resilience4jBulkheadConfigurationBuilder()
                .bulkheadConfig(BulkheadConfig.custom()
                        .maxConcurrentCalls(maxConcurrentCalls)
                        .maxWaitDuration(Duration.ofMillis(waitMillis))
                        .build())
                .build());
    }

    private Resilience4JConfigBuilder.Resilience4JCircuitBreakerConfiguration breaker(String id, long timeoutSeconds) {
        return new Resilience4JConfigBuilder(id)
                .circuitBreakerConfig(io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.ofDefaults())
                .timeLimiterConfig(TimeLimiterConfig.custom()
                        .timeoutDuration(Duration.ofSeconds(timeoutSeconds))
                        .build())
                .build();
    }
}
