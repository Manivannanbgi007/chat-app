package com.chatapp.user.kafka.consumer;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The standard wrapper around every Kafka event (see KAFKA_EVENT_DESIGN.md).
 * This class is copied in each service that needs it; services do not share code.
 * Integer (not int) so a missing field is null instead of silently 0.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope(
        String eventId,
        String eventType,
        String aggregateId,
        String occurredAt,
        Integer schemaVersion,
        String correlationId,
        UserRegisteredPayload payload) {

    /** The data inside a UserRegisteredEvent. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UserRegisteredPayload(
            String userId,
            String username,
            String email,
            String phone,
            String displayName) {
    }
}
