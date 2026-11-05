package com.chatapp.notification.kafka;

import java.util.UUID;
import java.util.regex.Pattern;

import com.chatapp.notification.config.CorrelationIdFilter;
import com.chatapp.notification.config.KafkaConfig;
import com.chatapp.notification.service.NotificationEventService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Listens to call.events (written by call-signaling-service, Phase 7) and tells people about calls they missed:
 *  - CallEndedEvent with outcome MISSED (nobody answered, or the caller hung up first) -> the callee: "Missed ... call"
 *  - CallEndedEvent with outcome BUSY (the callee was in another call)                -> the callee: "Missed ... call"
 *  - outcome ANSWERED or REJECTED: nothing (the callee took the call or said no themselves)
 * Every other event type is ignored. Broken events go straight to "call.events.dlt" (see KafkaConfig).
 */
@Component
public class CallEventConsumer {

    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Logger log = LoggerFactory.getLogger(CallEventConsumer.class);

    private final JsonMapper jsonMapper;
    private final NotificationEventService eventService;

    public CallEventConsumer(JsonMapper jsonMapper, NotificationEventService eventService) {
        this.jsonMapper = jsonMapper;
        this.eventService = eventService;
    }

    @KafkaListener(topics = KafkaConfig.CALL_EVENTS_TOPIC)
    public void onCallEvent(ConsumerRecord<String, String> record) {
        JsonNode event = EventReader.parse(jsonMapper, record.value());
        String correlationId = EventReader.text(event, "correlationId");
        if (correlationId != null && VALID_ID.matcher(correlationId).matches()) {
            MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
        }
        try {
            String type = EventReader.text(event, "eventType");
            if (type == null) {
                throw new InvalidEventException("Field eventType is missing");
            }
            if ("CallEndedEvent".equals(type)) {
                handleCallEnded(event);
            } else {
                log.debug("Ignoring event type {}", type);
            }
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    private void handleCallEnded(JsonNode event) {
        JsonNode payload = EventReader.requireObject(event, "payload");
        UUID eventId = EventReader.requireUuid(event, "eventId");
        UUID callerId = EventReader.requireUuid(payload, "callerId");
        UUID calleeId = EventReader.requireUuid(payload, "calleeId");
        String outcome = EventReader.text(payload, "outcome");
        String callType = EventReader.text(payload, "type");
        if (outcome == null) {
            throw new InvalidEventException("Field outcome is missing");
        }
        if (!"AUDIO".equals(callType) && !"VIDEO".equals(callType)) {
            throw new InvalidEventException("Field type must be AUDIO or VIDEO");
        }
        switch (outcome) {
            case "MISSED", "BUSY" -> eventService.onMissedCall(eventId, callerId, calleeId, "VIDEO".equals(callType));
            case "ANSWERED", "REJECTED" -> log.debug("Call outcome {} notifies nobody", outcome);
            // a value added later by call-signaling-service: nothing we know how to notify about
            default -> log.warn("Ignoring call event {} with unknown outcome", eventId);
        }
    }
}
