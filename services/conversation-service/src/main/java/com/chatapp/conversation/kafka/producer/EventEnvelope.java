package com.chatapp.conversation.kafka.producer;

import java.time.Instant;
import java.util.UUID;

/**
 * The wrapper around every Kafka event we send (see KAFKA_EVENT_DESIGN.md). Same shape in every
 * service (copied, not shared): eventId lets consumers ignore duplicates, schemaVersion allows safe changes.
 */
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        UUID aggregateId,
        Instant occurredAt,
        int schemaVersion,
        String correlationId,
        T payload) {
}
