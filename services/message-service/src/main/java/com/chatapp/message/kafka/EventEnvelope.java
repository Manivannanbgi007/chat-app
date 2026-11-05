package com.chatapp.message.kafka;

import java.time.Instant;
import java.util.UUID;

/**
 * The wrapper around every Kafka event. Same shape in every service (copied, not shared):
 * the eventId lets consumers ignore duplicates, schemaVersion allows safe changes later.
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
