package com.chatapp.notification.client;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/** Asks presence-service who has a live device right now. */
public interface PresenceClient {

    /**
     * The users (of those asked) that are online. When presence-service cannot be reached the answer is EMPTY,
     * i.e. "everybody is offline": a notification too many is better than one missed.
     */
    Set<UUID> online(Collection<UUID> userIds);
}
