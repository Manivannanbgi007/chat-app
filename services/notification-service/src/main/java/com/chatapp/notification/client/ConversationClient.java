package com.chatapp.notification.client;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Asks conversation-service (the owner of chats, groups and mutes). */
public interface ConversationClient {

    /** Empty when the conversation does not exist. Throws DependencyUnavailableException when the service is down. */
    Optional<ConversationInfo> find(UUID conversationId);

    /** The members who muted the chat right now. Empty for an unknown conversation. Throws when the service is down. */
    Set<UUID> mutedUserIds(UUID conversationId);

    /** type is PRIVATE or GROUP; name is the group name (null for private chats). */
    record ConversationInfo(UUID id, String type, String name) {

        public boolean isGroup() {
            return "GROUP".equals(type);
        }
    }
}
