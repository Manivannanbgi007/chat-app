package com.chatapp.conversation.kafka.consumer;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import java.util.regex.Pattern;

import com.chatapp.conversation.config.CorrelationIdFilter;
import com.chatapp.conversation.config.KafkaConfig;
import com.chatapp.conversation.service.LastMessageService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Listens to message.events and remembers the time of the newest message of each conversation. */
@Component
public class MessageEventConsumer {

    private static final String MESSAGE_CREATED = "MessageCreatedEvent";
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private static final Logger log = LoggerFactory.getLogger(MessageEventConsumer.class);

    private final JsonMapper jsonMapper;
    private final LastMessageService lastMessageService;

    public MessageEventConsumer(JsonMapper jsonMapper, LastMessageService lastMessageService) {
        this.jsonMapper = jsonMapper;
        this.lastMessageService = lastMessageService;
    }

    @KafkaListener(topics = KafkaConfig.MESSAGE_EVENTS_TOPIC)
    public void onMessageEvent(ConsumerRecord<String, String> record) {
        IncomingEvent event = parse(record.value());

        // Put the event's correlation id into the logs while we handle it.
        String correlationId = event.correlationId();
        if (correlationId != null && VALID_ID.matcher(correlationId).matches()) {
            MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
        }
        try {
            if (!MESSAGE_CREATED.equals(event.eventType())) {
                log.debug("Ignoring event type {}", event.eventType());
                return;
            }
            handleMessageCreated(event);
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    private void handleMessageCreated(IncomingEvent event) {
        IncomingEvent.MessageCreatedPayload payload = event.payload();
        if (payload == null) {
            throw new InvalidEventException("MessageCreatedEvent has no payload");
        }
        UUID eventId = parseUuid(event.eventId(), "eventId");
        UUID conversationId = parseUuid(payload.conversationId(), "payload.conversationId");
        Instant createdAt = parseInstant(payload.createdAt(), "payload.createdAt");

        lastMessageService.messageCreated(eventId, conversationId, createdAt);
    }

    private IncomingEvent parse(String json) {
        if (json == null || json.isBlank()) {
            throw new InvalidEventException("Empty event");
        }
        try {
            return jsonMapper.readValue(json, IncomingEvent.class);
        } catch (JacksonException e) {
            throw new InvalidEventException("Event is not valid JSON", e);
        }
    }

    private static UUID parseUuid(String value, String field) {
        require(value, field);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidEventException("Field " + field + " is not a UUID");
        }
    }

    private static Instant parseInstant(String value, String field) {
        require(value, field);
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new InvalidEventException("Field " + field + " is not a time");
        }
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidEventException("Field " + field + " is missing");
        }
    }
}
