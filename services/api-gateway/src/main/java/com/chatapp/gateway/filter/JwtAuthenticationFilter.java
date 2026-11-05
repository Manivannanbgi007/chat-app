package com.chatapp.gateway.filter;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

/**
 * The gateway's "door check". Every request must carry a valid access token, except the
 * public endpoints (login, register, ...). A token is rejected when
 *   - the signature, issuer or expiry is wrong, or
 *   - its login session was ended (logout, password reset): auth-service leaves a Redis key for that.
 * The token is passed on unchanged: downstream services check it again themselves.
 * There is NO business logic here, only "is this token acceptable?".
 */
@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    /** Name of the exchange attribute holding the user id; the rate limiter reads it. */
    public static final String USER_ID_ATTRIBUTE = "authenticatedUserId";

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String REVOKED_KEY_PREFIX = "auth:revoked:";
    private static final String BEARER = "Bearer ";

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    /** Public endpoints: {HTTP method, path pattern}. "*" means any method. */
    private static final List<String[]> PUBLIC_ENDPOINTS = List.of(
            new String[]{"POST", "/api/auth/register"},
            new String[]{"POST", "/api/auth/login"},
            new String[]{"POST", "/api/auth/refresh"},
            new String[]{"POST", "/api/auth/logout"},
            new String[]{"POST", "/api/auth/verify-otp"},
            new String[]{"POST", "/api/auth/resend-otp"},
            new String[]{"POST", "/api/auth/forgot-password"},
            new String[]{"POST", "/api/auth/reset-password"},
            new String[]{"GET", "/api/auth/ping"},
            new String[]{"GET", "/service-docs/**"},
            // WebSocket handshake: browsers cannot send an Authorization header with it. The token is checked
            // by message-service inside the STOMP CONNECT frame instead (WEBSOCKET_DESIGN.md).
            new String[]{"GET", "/ws/**"},
            new String[]{"GET", "/fallback"},
            new String[]{"*", "/actuator/**"});

    private final ReactiveJwtDecoder jwtDecoder;
    private final ReactiveStringRedisTemplate redis;

    public JwtAuthenticationFilter(ReactiveJwtDecoder jwtDecoder, ReactiveStringRedisTemplate redis) {
        this.jwtDecoder = jwtDecoder;
        this.redis = redis;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (isPublic(exchange)) {
            return chain.filter(exchange);
        }

        String header = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith(BEARER) || header.length() <= BEARER.length()) {
            return Mono.error(unauthorized());
        }
        String token = header.substring(BEARER.length()).trim();

        return jwtDecoder.decode(token)
                .onErrorMap(e -> unauthorized())       // bad signature, expired, wrong issuer, garbage ...
                .flatMap(jwt -> rejectIfSessionRevoked(jwt).thenReturn(jwt))
                .flatMap(jwt -> {
                    // a token without a subject cannot identify anybody (and would break the per-user rate limit)
                    if (jwt.getSubject() == null || jwt.getSubject().isBlank()) {
                        return Mono.error(unauthorized());
                    }
                    exchange.getAttributes().put(USER_ID_ATTRIBUTE, jwt.getSubject());
                    return chain.filter(exchange);
                });
    }

    /** CORS pre-flight requests (OPTIONS) carry no token by design; the CORS filter answers them. */
    private boolean isPublic(ServerWebExchange exchange) {
        HttpMethod method = exchange.getRequest().getMethod();
        if (HttpMethod.OPTIONS.equals(method)) {
            return true;
        }
        String path = exchange.getRequest().getPath().value();
        for (String[] endpoint : PUBLIC_ENDPOINTS) {
            boolean methodMatches = "*".equals(endpoint[0]) || endpoint[0].equals(method.name());
            if (methodMatches && MATCHER.match(endpoint[1], path)) {
                return true;
            }
        }
        return false;
    }

    private Mono<Boolean> rejectIfSessionRevoked(Jwt jwt) {
        String sessionId = jwt.getClaimAsString("sid");
        if (sessionId == null) {
            return Mono.error(unauthorized());
        }
        return redis.hasKey(REVOKED_KEY_PREFIX + sessionId)
                .onErrorResume(e -> {
                    // Redis down: we cannot check. Let the request through; the token expires in at most 15 minutes.
                    log.warn("Could not check session revocation in Redis ({})", e.getClass().getSimpleName());
                    return Mono.just(false);
                })
                .flatMap(revoked -> revoked ? Mono.<Boolean>error(unauthorized()) : Mono.just(false));
    }

    private ResponseStatusException unauthorized() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }

    /** Runs right after the correlation ID filter and before the route filters (rate limiter, circuit breaker). */
    @Override
    public int getOrder() {
        return -100;
    }
}
