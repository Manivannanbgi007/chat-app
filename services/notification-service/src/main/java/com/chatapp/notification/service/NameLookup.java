package com.chatapp.notification.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.chatapp.notification.client.UserClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Display names for notification texts. A name is remembered for a few minutes, so a burst of messages from one
 * person costs one call to user-service, not one per message. If the name cannot be found we say "Someone".
 */
@Component
public class NameLookup {

    private record CachedName(String name, Instant expiresAt) {
    }

    private static final int MAX_ENTRIES = 5000;

    private final UserClient userClient;
    private final Duration lifetime;
    private final Map<UUID, CachedName> cache = new ConcurrentHashMap<>();

    public NameLookup(UserClient userClient, @Value("${chatapp.names.cache-minutes:5}") long cacheMinutes) {
        this.userClient = userClient;
        this.lifetime = Duration.ofMinutes(cacheMinutes);
    }

    /** viewerId is only needed by user-service to answer; the display name is the same for everybody. */
    public String displayName(UUID userId, UUID viewerId) {
        CachedName cached = cache.get(userId);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.name();
        }
        String name = userClient.displayName(userId, viewerId).orElse(null);
        if (name == null) {
            return NotificationRules.UNKNOWN_PERSON;   // not cached: try again next time
        }
        if (cache.size() > MAX_ENTRIES) {
            cache.clear();      // a crude limit is enough for a cache of short-lived names
        }
        cache.put(userId, new CachedName(name, Instant.now().plus(lifetime)));
        return name;
    }
}
