package com.chatapp.presence.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * What a viewer may know about a user.
 * visible = false: the viewer is not allowed to see it, online and lastSeenAt are then null (never a guess).
 * online / lastSeenAt use wrapper types because they are null when hidden.
 */
public record PresenceResponse(UUID userId, boolean visible, Boolean online, Instant lastSeenAt) {

    public static PresenceResponse hidden(UUID userId) {
        return new PresenceResponse(userId, false, null, null);
    }
}
