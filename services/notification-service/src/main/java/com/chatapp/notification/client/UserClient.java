package com.chatapp.notification.client;

import java.util.Optional;
import java.util.UUID;

/** Asks user-service (the owner of profiles) for a display name. */
public interface UserClient {

    /**
     * The display name of a user, as the viewer may see it. Empty when unknown or when user-service cannot be reached
     * (a missing name must never stop a notification: the caller falls back to "Someone").
     */
    Optional<String> displayName(UUID userId, UUID viewerId);
}
