package com.chatapp.conversation.kafka.producer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.chatapp.conversation.enums.ConversationType;

/**
 * Data of a ConversationCreatedEvent. Every participant joined at createdAt.
 * groupName and createdByName are only filled for groups (message-service writes the "created group" message).
 * Fields are only ever ADDED to an event (schemaVersion stays 1), so old consumers keep working.
 */
public record ConversationCreatedPayload(
        UUID conversationId,
        ConversationType type,
        List<UUID> participantIds,
        Instant createdAt,
        UUID createdBy,
        String groupName,
        String createdByName) {
}
