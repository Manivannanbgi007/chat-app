package com.chatapp.conversation.dto.request;

import java.util.List;
import java.util.UUID;

import com.chatapp.conversation.enums.ConversationType;
import jakarta.validation.constraints.NotNull;

/**
 * Body of POST /api/conversations.
 *  - PRIVATE: userId = the other person.
 *  - GROUP:   name (1-100), description (optional, max 500), memberIds (the other members; I am added as admin).
 * Which fields are needed depends on the type, so they are checked in the service (the name is trimmed first).
 */
public record CreateConversationRequest(
        @NotNull ConversationType type,
        UUID userId,
        String name,
        String description,
        List<UUID> memberIds) {

    /** A private conversation with one other user. */
    public CreateConversationRequest(ConversationType type, UUID userId) {
        this(type, userId, null, null, null);
    }

    public static CreateConversationRequest group(String name, String description, List<UUID> memberIds) {
        return new CreateConversationRequest(ConversationType.GROUP, null, name, description, memberIds);
    }
}
