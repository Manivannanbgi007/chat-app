package com.chatapp.presence.client;

import java.util.UUID;

/** Asks user-service (the owner of privacy settings, contacts and blocks) who may see whose status. */
public interface UserClient {

    /**
     * May the viewer see the online status / last seen of the target?
     * Throws DependencyUnavailableException when user-service cannot answer: we then show NOTHING (fail safe).
     */
    boolean canSeePresence(UUID viewerId, UUID targetId);
}
