package com.chatapp.presence.repository;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.chatapp.presence.config.PresenceProperties;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

/**
 * All Redis access of the service. Two keys per user:
 *
 *   presence:devices:{userId}    sorted set. Member = device id (the login session id),
 *                                score = the moment (epoch millis) when that device stops counting as online.
 *   presence:lastactive:{userId} the time (epoch millis) of the last heartbeat or "offline" call of the user.
 *
 * A user is online while at least one device has a score in the future. A device that stops sending heartbeats
 * (closed tab, crash, lost connection) simply expires. Both keys also have a Redis TTL so nothing stays forever.
 */
@Repository
public class PresenceRepository {

    private static final String DEVICES_PREFIX = "presence:devices:";
    private static final String LAST_ACTIVE_PREFIX = "presence:lastactive:";
    private static final long LAST_ACTIVE_TTL_SECONDS = Duration.ofDays(30).toSeconds();
    /** The devices key lives a little longer than a device can, so Redis cleans up empty users by itself. */
    private static final long KEY_TTL_MARGIN_SECONDS = 10;

    /**
     * Heartbeat, as ONE atomic Lua script (so parallel heartbeats cannot interleave):
     * drop expired devices, add/refresh this device, refresh the key TTL, remember the last active time.
     * KEYS: devices key, last-active key. ARGV: now, expiresAt, deviceId, devicesKeyTtl, lastActiveTtl.
     */
    private static final DefaultRedisScript<Long> HEARTBEAT_SCRIPT = new DefaultRedisScript<>("""
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
            redis.call('ZADD', KEYS[1], ARGV[2], ARGV[3])
            redis.call('EXPIRE', KEYS[1], ARGV[4])
            redis.call('SET', KEYS[2], ARGV[1], 'EX', ARGV[5])
            return 1
            """, Long.class);

    /** This device is gone now: remove it and remember the time. KEYS as above. ARGV: now, deviceId, lastActiveTtl. */
    private static final DefaultRedisScript<Long> OFFLINE_SCRIPT = new DefaultRedisScript<>("""
            redis.call('ZREM', KEYS[1], ARGV[2])
            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1])
            redis.call('SET', KEYS[2], ARGV[1], 'EX', ARGV[3])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final Clock clock;
    private final long ttlMillis;

    public PresenceRepository(StringRedisTemplate redis, Clock clock, PresenceProperties properties) {
        this.redis = redis;
        this.clock = clock;
        this.ttlMillis = Duration.ofSeconds(properties.ttlSeconds()).toMillis();
    }

    public static String devicesKey(UUID userId) {
        return DEVICES_PREFIX + userId;
    }

    public static String lastActiveKey(UUID userId) {
        return LAST_ACTIVE_PREFIX + userId;
    }

    /** "This device is alive": it counts as online for the next ttl seconds. */
    public void heartbeat(UUID userId, UUID deviceId) {
        long now = clock.millis();
        redis.execute(HEARTBEAT_SCRIPT, List.of(devicesKey(userId), lastActiveKey(userId)),
                String.valueOf(now),
                String.valueOf(now + ttlMillis),
                deviceId.toString(),
                String.valueOf(ttlMillis / 1000 + KEY_TTL_MARGIN_SECONDS),
                String.valueOf(LAST_ACTIVE_TTL_SECONDS));
    }

    /** "This device is gone": offline immediately (the user stays online if another device is alive). */
    public void markOffline(UUID userId, UUID deviceId) {
        redis.execute(OFFLINE_SCRIPT, List.of(devicesKey(userId), lastActiveKey(userId)),
                String.valueOf(clock.millis()), deviceId.toString(), String.valueOf(LAST_ACTIVE_TTL_SECONDS));
    }

    /** True when at least one device of the user is alive right now. */
    public boolean isOnline(UUID userId) {
        // scores are whole milliseconds, so "score > now" is "score >= now + 1"
        Long alive = redis.opsForZSet().count(devicesKey(userId), clock.millis() + 1, Double.POSITIVE_INFINITY);
        return alive != null && alive > 0;
    }

    /** The time of the last heartbeat / offline call, or null when the user was never seen (or not for 30 days). */
    public Instant lastActive(UUID userId) {
        String value = redis.opsForValue().get(lastActiveKey(userId));
        return value == null ? null : Instant.ofEpochMilli(Long.parseLong(value));
    }

    /** Which of these users have a live device? One round trip for all of them (pipelined). */
    public Set<UUID> onlineAmong(Collection<UUID> userIds) {
        List<UUID> ids = new ArrayList<>(new LinkedHashSet<>(userIds));
        if (ids.isEmpty()) {
            return Set.of();
        }
        long now = clock.millis();
        List<Object> counts = redis.executePipelined((RedisCallback<Object>) connection -> {
            for (UUID id : ids) {
                connection.zSetCommands().zCount(devicesKey(id).getBytes(StandardCharsets.UTF_8),
                        Range.rightUnbounded(Range.Bound.exclusive(now)));
            }
            return null;
        });
        Set<UUID> online = new LinkedHashSet<>();
        for (int i = 0; i < ids.size(); i++) {
            Object count = counts.get(i);
            if (count instanceof Long alive && alive > 0) {
                online.add(ids.get(i));
            }
        }
        return online;
    }
}
