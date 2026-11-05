package com.chatapp.user.kafka.consumer;

import java.util.UUID;
import java.util.regex.Pattern;

import com.chatapp.user.config.CorrelationIdFilter;
import com.chatapp.user.service.UserRegistrationService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** Listens to user.events and creates a profile for each UserRegisteredEvent. */
@Component
public class UserEventConsumer {

    public static final String TOPIC = "user.events";
    private static final String USER_REGISTERED = "UserRegisteredEvent";
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private static final Logger log = LoggerFactory.getLogger(UserEventConsumer.class);

    private final JsonMapper jsonMapper;
    private final UserRegistrationService registrationService;

    public UserEventConsumer(JsonMapper jsonMapper, UserRegistrationService registrationService) {
        this.jsonMapper = jsonMapper;
        this.registrationService = registrationService;
    }

    @KafkaListener(topics = TOPIC)
    public void onUserEvent(ConsumerRecord<String, String> record) {
        EventEnvelope envelope = parse(record.value());

        // Put the event's correlation id into the logs while we handle it.
        String correlationId = envelope.correlationId();
        if (correlationId != null && VALID_ID.matcher(correlationId).matches()) {
            MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
        }
        try {
            if (!USER_REGISTERED.equals(envelope.eventType())) {
                log.debug("Ignoring event type {}", envelope.eventType());
                return;
            }
            handleUserRegistered(envelope);
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    private void handleUserRegistered(EventEnvelope envelope) {
        EventEnvelope.UserRegisteredPayload payload = envelope.payload();
        if (payload == null) {
            throw new InvalidEventException("UserRegisteredEvent has no payload");
        }
        UUID eventId = parseUuid(envelope.eventId(), "eventId");
        UUID userId = parseUuid(payload.userId(), "payload.userId");
        require(payload.username(), "payload.username");
        require(payload.email(), "payload.email");
        require(payload.displayName(), "payload.displayName");

        registrationService.createUser(eventId, userId, payload.username(), payload.email(),
                payload.phone(), payload.displayName());
    }

    private EventEnvelope parse(String json) {
        if (json == null || json.isBlank()) {
            throw new InvalidEventException("Empty event");
        }
        try {
            return jsonMapper.readValue(json, EventEnvelope.class);
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

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new InvalidEventException("Field " + field + " is missing");
        }
    }
}
