package com.chatapp.message.kafka;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Payloads of the Kafka events this service publishes (inside an EventEnvelope).
 * They carry ids and times only, never the message text.
 */
public final class MessageEvents {

    private MessageEvents() {
    }

    public record MessageCreated(UUID messageId, UUID conversationId, UUID senderId, String type,
                                 Instant createdAt, List<UUID> recipientIds) {
    }

    public record MessageDelivered(UUID messageId, UUID conversationId, UUID senderId, UUID recipientId,
                                   UUID deviceId, Instant at) {
    }

    public record MessageRead(UUID messageId, UUID conversationId, UUID senderId, UUID recipientId,
                              UUID deviceId, Instant at) {
    }
}
