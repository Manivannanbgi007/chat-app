package com.chatapp.gateway.config;

import java.net.InetSocketAddress;

import com.chatapp.gateway.filter.JwtAuthenticationFilter;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.cloud.gateway.support.ipresolver.XForwardedRemoteAddressResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * Decides WHO a request is counted against when rate limiting (the limits themselves are in application.yml).
 * - ipKeyResolver: by client IP, used on public auth endpoints (brute-force protection).
 * - userKeyResolver: by user id from the token, used on the other APIs (falls back to the IP).
 */
@Configuration
public class RateLimitConfig {

    // We sit behind exactly one trusted proxy (nginx / a load balancer): trust the last X-Forwarded-For entry.
    private static final XForwardedRemoteAddressResolver ADDRESS_RESOLVER =
            XForwardedRemoteAddressResolver.maxTrustedIndex(1);

    /** @Primary: Spring Cloud Gateway needs one default resolver; routes still name theirs explicitly. */
    @Bean
    @Primary
    public KeyResolver ipKeyResolver() {
        return exchange -> Mono.just(clientIp(exchange));
    }

    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> {
            Object userId = exchange.getAttribute(JwtAuthenticationFilter.USER_ID_ATTRIBUTE);
            return Mono.just(userId != null ? "user:" + userId : clientIp(exchange));
        };
    }

    private String clientIp(ServerWebExchange exchange) {
        InetSocketAddress address = ADDRESS_RESOLVER.resolve(exchange);
        return address != null && address.getAddress() != null ? address.getAddress().getHostAddress() : "unknown";
    }
}
