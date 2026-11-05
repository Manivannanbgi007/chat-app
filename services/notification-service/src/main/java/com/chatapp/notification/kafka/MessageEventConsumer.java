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
 * Listens to message.events:
 *  - MessageCreatedEvent (not SYSTEM) -> "new messages" notifications for offline recipients
 *  - MessageReadEvent                 -> marks that chat's notification as read
 * Every other event type (delivered, ...) is ignored.
 */
@Component
public class MessageEventConsumer {

    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Logger log = LoggerFactory.getLogger(MessageEventConsumer.class);

    private final JsonMapper jsonMapper;
    private final NotificationEventService eventService;

    public MessageEventConsumer(JsonMapper jsonMapper, NotificationEventService eventService) {
        this.jsonMapper = jsonMapper;
        this.eventService = eventService;
    }

    @KafkaListener(topics = KafkaConfig.MESSAGE_EVENTS_TOPIC)
    public void onMessageEvent(ConsumerRecord<String, String> record) {
        JsonNode event = EventReader.parse(jsonMapper, record.value());
        String correlationId = EventReader.text(event, "correlationId");
        if (correlationId != null && VALID_ID.matcher(correlationId).matches()) {
            MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);     // the event's id shows up in our log lines
        }
        try {
            String type = EventReader.text(event, "eventType");
            if ("MessageCreatedEvent".equals(type)) {
                handleCreated(event);
            } else if ("MessageReadEvent".equals(type)) {
                handleRead(event);
            } else {
                log.debug("Ignoring event type {}", type);
            }
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    private void handleCreated(JsonNode event) {
        UUID eventId = EventReader.requireUuid(event, "eventId");
        JsonNode payload = EventReader.requireObject(event, "payload");
        if ("SYSTEM".equals(EventReader.text(payload, "type"))) {
            return;       // group change notes are not messages
        }
        eventService.onMessageCreated(eventId,
                EventReader.requireUuid(payload, "conversationId"),
                EventReader.requireUuid(payload, "senderId"),
                EventReader.uuidList(payload, "recipientIds"));
    }

    private void handleRead(JsonNode event) {
        UUID eventId = EventReader.requireUuid(event, "eventId");
        JsonNode payload = EventReader.requireObject(event, "payload");
        eventService.onMessageRead(eventId,
                EventReader.requireUuid(payload, "recipientId"),
                EventReader.requireUuid(payload, "conversationId"));
    }
}
