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
 * Listens to conversation.events and notifies people about changes that concern them:
 *  - ConversationCreatedEvent of a GROUP     -> everybody except the creator: "X added you"
 *  - GroupMembersAddedEvent                  -> the added members: "X added you"
 *  - GroupMemberRemovedEvent (REMOVED only)  -> the removed member: "X removed you"  (LEFT notifies nobody)
 *  - GroupRoleChangedEvent (role ADMIN)      -> that member: "You are now an admin"
 * Everything else is ignored.
 */
@Component
public class ConversationEventConsumer {

    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Logger log = LoggerFactory.getLogger(ConversationEventConsumer.class);

    private final JsonMapper jsonMapper;
    private final NotificationEventService eventService;

    public ConversationEventConsumer(JsonMapper jsonMapper, NotificationEventService eventService) {
        this.jsonMapper = jsonMapper;
        this.eventService = eventService;
    }

    @KafkaListener(topics = KafkaConfig.CONVERSATION_EVENTS_TOPIC)
    public void onConversationEvent(ConsumerRecord<String, String> record) {
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
            switch (type) {
                case "ConversationCreatedEvent" -> handleCreated(event);
                case "GroupMembersAddedEvent" -> handleMembersAdded(event);
                case "GroupMemberRemovedEvent" -> handleMemberRemoved(event);
                case "GroupRoleChangedEvent" -> handleRoleChanged(event);
                default -> log.debug("Ignoring event type {}", type);
            }
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    private void handleCreated(JsonNode event) {
        JsonNode payload = EventReader.requireObject(event, "payload");
        if (!"GROUP".equals(EventReader.text(payload, "type"))) {
            return;       // private chats do not notify anybody
        }
        eventService.onGroupCreated(EventReader.requireUuid(event, "eventId"),
                EventReader.requireUuid(payload, "conversationId"),
                EventReader.requireUuid(payload, "createdBy"),
                EventReader.text(payload, "createdByName"),
                EventReader.text(payload, "groupName"),
                EventReader.uuidList(payload, "participantIds"));
    }

    private void handleMembersAdded(JsonNode event) {
        JsonNode payload = EventReader.requireObject(event, "payload");
        eventService.onMembersAdded(EventReader.requireUuid(event, "eventId"),
                EventReader.requireUuid(payload, "conversationId"),
                EventReader.requireUuid(payload, "actorId"),
                EventReader.text(payload, "actorName"),
                EventReader.userIdsOfMembers(payload, "members"));
    }

    private void handleMemberRemoved(JsonNode event) {
        JsonNode payload = EventReader.requireObject(event, "payload");
        UUID eventId = EventReader.requireUuid(event, "eventId");
        UUID conversationId = EventReader.requireUuid(payload, "conversationId");
        UUID userId = EventReader.requireUuid(payload, "userId");
        if (!"REMOVED".equals(EventReader.text(payload, "reason"))) {
            return;       // somebody who LEFT knows it already
        }
        eventService.onMemberRemoved(eventId, conversationId, EventReader.text(payload, "actorName"), userId);
    }

    private void handleRoleChanged(JsonNode event) {
        JsonNode payload = EventReader.requireObject(event, "payload");
        UUID eventId = EventReader.requireUuid(event, "eventId");
        UUID conversationId = EventReader.requireUuid(payload, "conversationId");
        UUID userId = EventReader.requireUuid(payload, "userId");
        if (!"ADMIN".equals(EventReader.text(payload, "role"))) {
            return;
        }
        eventService.onMadeAdmin(eventId, conversationId, userId);
    }
}
