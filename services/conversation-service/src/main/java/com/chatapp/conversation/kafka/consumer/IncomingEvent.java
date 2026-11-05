package com.chatapp.conversation.kafka.consumer;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * An incoming Kafka event (the standard envelope, see KAFKA_EVENT_DESIGN.md).
 * Everything is a String / Integer so a missing or malformed field becomes null and
 * we can report it clearly instead of crashing inside the JSON parser.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IncomingEvent(
        String eventId,
        String eventType,
        String aggregateId,
        String occurredAt,
        Integer schemaVersion,
        String correlationId,
        MessageCreatedPayload payload) {

    /** The data inside a MessageCreatedEvent. There is no message text in it, on purpose. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MessageCreatedPayload(
            String messageId,
            String conversationId,
            String senderId,
            String type,
            String createdAt,
            List<String> recipientIds) {
    }
}
