package com.chatapp.auth.service;

import java.time.Duration;
import java.util.UUID;

import com.chatapp.auth.config.AuthProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Access tokens live for 15 minutes and cannot be "taken back". When a session ends we write a
 * Redis key for that session; the gateway rejects tokens whose session id has such a key.
 * The key only has to live as long as the longest-living access token.
 */
@Service
public class SessionRevocationService {

    public static final String KEY_PREFIX = "auth:revoked:";

    private static final Logger log = LoggerFactory.getLogger(SessionRevocationService.class);

    private final StringRedisTemplate redis;
    private final Duration keyLifetime;

    public SessionRevocationService(StringRedisTemplate redis, AuthProperties properties) {
        this.redis = redis;
        this.keyLifetime = Duration.ofMinutes(properties.security().accessTokenMinutes() + 1L);
    }

    public void markRevoked(UUID sessionId) {
        try {
            redis.opsForValue().set(KEY_PREFIX + sessionId, "1", keyLifetime);
        } catch (Exception e) {
            // The session is already revoked in MySQL, so refresh is blocked either way.
            // Only the already-issued access token stays valid until it expires.
            log.warn("Could not write session revocation to Redis ({})", e.getClass().getSimpleName());
        }
    }
}
