package com.chatapp.message.kafka.consumer;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import com.chatapp.message.config.CorrelationIdFilter;
import com.chatapp.message.config.KafkaConsumerConfig;
import com.chatapp.message.kafka.consumer.IncomingConversationEvent.Payload;
import com.chatapp.message.service.GroupEventService;
import com.chatapp.message.service.SystemMessageContent.UserRef;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Listens to conversation.events (written by conversation-service): checks and converts the event, then lets
 * GroupEventService do the work in one transaction. A broken event throws InvalidEventException and goes straight to
 * the dead-letter topic; any other failure is retried (see KafkaConsumerConfig).
 */
@Component
public class ConversationEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(ConversationEventConsumer.class);
    private static final Pattern VALID_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private final JsonMapper jsonMapper;
    private final GroupEventService groupEventService;

    public ConversationEventConsumer(JsonMapper jsonMapper, GroupEventService groupEventService) {
        this.jsonMapper = jsonMapper;
        this.groupEventService = groupEventService;
    }

    @KafkaListener(topics = KafkaConsumerConfig.CONVERSATION_EVENTS_TOPIC)
    public void onConversationEvent(ConsumerRecord<String, String> record) {
        IncomingConversationEvent event = parse(record.value());

        // Put the event's correlation id into the logs while we handle it.
        String correlationId = event.correlationId();
        if (correlationId != null && VALID_ID.matcher(correlationId).matches()) {
            MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
        }
        try {
            handle(event);
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    private void handle(IncomingConversationEvent event) {
        String type = event.eventType();
        if (type == null) {
            throw new InvalidEventException("Field eventType is missing");
        }
        switch (type) {
            case "ConversationCreatedEvent" -> created(event);
            case "GroupMembersAddedEvent" -> membersAdded(event);
            case "GroupMemberRemovedEvent" -> memberRemoved(event);
            case "GroupRoleChangedEvent" -> roleChanged(event);
            case "GroupUpdatedEvent" -> updated(event);
            default -> log.debug("Ignoring event type {}", type);
        }
    }

    private void created(IncomingConversationEvent event) {
        Payload p = payload(event);
        String type = p.type();
        if (!"PRIVATE".equals(type) && !"GROUP".equals(type)) {
            throw new InvalidEventException("Field payload.type must be PRIVATE or GROUP");
        }
        if (p.participantIds() == null) {
            throw new InvalidEventException("Field payload.participantIds is missing");
        }
        List<UUID> participants = p.participantIds().stream().map(id -> uuid(id, "payload.participantIds")).toList();
        UserRef creator = null;
        if ("GROUP".equals(type)) {
            creator = new UserRef(uuid(p.createdBy(), "payload.createdBy"), p.createdByName());
        }
        groupEventService.conversationCreated(eventId(event), conversationId(event), type, participants,
                instant(p.createdAt(), "payload.createdAt"), creator, p.groupName());
    }

    private void membersAdded(IncomingConversationEvent event) {
        Payload p = payload(event);
        if (p.members() == null || p.members().isEmpty()) {
            throw new InvalidEventException("Field payload.members is missing");
        }
        List<UserRef> members = p.members().stream()
                .map(m -> new UserRef(uuid(m == null ? null : m.userId(), "payload.members.userId"), m.name()))
                .toList();
        groupEventService.membersAdded(eventId(event), conversationId(event), actor(p), members,
                instant(p.joinedAt(), "payload.joinedAt"));
    }

    private void memberRemoved(IncomingConversationEvent event) {
        Payload p = payload(event);
        boolean left;
        if ("LEFT".equals(p.reason())) {
            left = true;
        } else if ("REMOVED".equals(p.reason())) {
            left = false;
        } else {
            throw new InvalidEventException("Field payload.reason must be REMOVED or LEFT");
        }
        UserRef user = new UserRef(uuid(p.userId(), "payload.userId"), p.userName());
        groupEventService.memberRemoved(eventId(event), conversationId(event), actor(p), user, left,
                instant(p.leftAt(), "payload.leftAt"));
    }

    private void roleChanged(IncomingConversationEvent event) {
        Payload p = payload(event);
        boolean admin;
        if ("ADMIN".equals(p.role())) {
            admin = true;
        } else if ("MEMBER".equals(p.role())) {
            admin = false;
        } else {
            throw new InvalidEventException("Field payload.role must be ADMIN or MEMBER");
        }
        // actorId null = automatic (the last admin left)
        UserRef actor = p.actorId() == null ? null : actor(p);
        UserRef user = new UserRef(uuid(p.userId(), "payload.userId"), p.userName());
        groupEventService.roleChanged(eventId(event), conversationId(event), actor, user, admin,
                instant(p.at(), "payload.at"));
    }

    private void updated(IncomingConversationEvent event) {
        Payload p = payload(event);
        groupEventService.groupUpdated(eventId(event), conversationId(event), actor(p), instant(p.at(), "payload.at"),
                p.name(), Boolean.TRUE.equals(p.descriptionChanged()), p.onlyAdminsCanSend(), p.onlyAdminsCanEdit());
    }

    // ------------------------------------------------------------------ parsing

    private IncomingConversationEvent parse(String json) {
        if (json == null || json.isBlank()) {
            throw new InvalidEventException("Empty event");
        }
        try {
            return jsonMapper.readValue(json, IncomingConversationEvent.class);
        } catch (JacksonException e) {
            throw new InvalidEventException("Event is not valid JSON", e);
        }
    }

    private static Payload payload(IncomingConversationEvent event) {
        if (event.payload() == null) {
            throw new InvalidEventException(event.eventType() + " has no payload");
        }
        return event.payload();
    }

    private static UUID eventId(IncomingConversationEvent event) {
        return uuid(event.eventId(), "eventId");
    }

    private static UUID conversationId(IncomingConversationEvent event) {
        String value = event.payload().conversationId() != null ? event.payload().conversationId() : event.aggregateId();
        return uuid(value, "payload.conversationId");
    }

    private static UserRef actor(Payload p) {
        return new UserRef(uuid(p.actorId(), "payload.actorId"), p.actorName());
    }

    private static UUID uuid(String value, String field) {
        require(value, field);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new InvalidEventException("Field " + field + " is not a UUID");
        }
    }

    private static Instant instant(String value, String field) {
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
