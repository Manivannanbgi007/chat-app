package com.chatapp.presence.service;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import com.chatapp.presence.client.UserClient;
import com.chatapp.presence.dto.response.PresenceResponse;
import com.chatapp.presence.exception.DependencyUnavailableException;
import com.chatapp.presence.repository.PresenceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/**
 * Presence rules. Heartbeats come from the app every 20 seconds; everything else is derived from them.
 * Redis problems become 503 DEPENDENCY_UNAVAILABLE, so a caller can tell "we do not know" from "offline".
 */
@Service
public class PresenceService {

    private static final Logger log = LoggerFactory.getLogger(PresenceService.class);

    private final PresenceRepository repository;
    private final UserClient userClient;

    public PresenceService(PresenceRepository repository, UserClient userClient) {
        this.repository = repository;
        this.userClient = userClient;
    }

    public void heartbeat(UUID userId, UUID deviceId) {
        redis(() -> {
            repository.heartbeat(userId, deviceId);
            return null;
        });
    }

    public void goOffline(UUID userId, UUID deviceId) {
        redis(() -> {
            repository.markOffline(userId, deviceId);
            return null;
        });
    }

    /**
     * What the viewer may know about the target.
     * You always see yourself. For everybody else user-service decides (blocks, "last seen" setting, contacts);
     * when it cannot answer we show nothing (503) instead of guessing.
     */
    public PresenceResponse presenceOf(UUID viewerId, UUID targetId) {
        if (!viewerId.equals(targetId) && !userClient.canSeePresence(viewerId, targetId)) {
            return PresenceResponse.hidden(targetId);
        }
        return redis(() -> {
            boolean online = repository.isOnline(targetId);
            Instant lastSeenAt = repository.lastActive(targetId);
            return new PresenceResponse(targetId, true, online, lastSeenAt);
        });
    }

    /** For other services (no privacy): which of these users are online right now? */
    public List<UUID> onlineAmong(Collection<UUID> userIds) {
        return List.copyOf(redis(() -> repository.onlineAmong(userIds)));
    }

    private <T> T redis(Supplier<T> action) {
        try {
            return action.get();
        } catch (DataAccessException e) {
            log.warn("Redis is not available ({})", e.getClass().getSimpleName());
            throw new DependencyUnavailableException("Presence storage is not available");
        }
    }
}
