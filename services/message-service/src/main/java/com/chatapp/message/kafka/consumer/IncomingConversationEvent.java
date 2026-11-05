package com.chatapp.message.kafka.consumer;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * An event from conversation-service (topic conversation.events, standard envelope, see KAFKA_EVENT_DESIGN.md).
 *
 * Everything is a String / Boolean so a missing or malformed field becomes null and we can report it clearly
 * instead of crashing inside the JSON parser. ONE payload type holds the fields of every event type; each
 * event type only fills its own fields:
 *  - ConversationCreatedEvent: conversationId, type, participantIds, createdAt, createdBy, groupName, createdByName
 *  - GroupMembersAddedEvent:   conversationId, actorId, actorName, members, joinedAt
 *  - GroupMemberRemovedEvent:  conversationId, actorId, actorName, userId, userName, reason, leftAt
 *  - GroupRoleChangedEvent:    conversationId, actorId, actorName, userId, userName, role, at
 *  - GroupUpdatedEvent:        conversationId, actorId, actorName, at, name, descriptionChanged,
 *                              onlyAdminsCanSend, onlyAdminsCanEdit
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record IncomingConversationEvent(
        String eventId,
        String eventType,
        String aggregateId,
        String occurredAt,
        Integer schemaVersion,
        String correlationId,
        Payload payload) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Payload(
            String conversationId,
            // ConversationCreatedEvent
            String type,
            List<String> participantIds,
            String createdAt,
            String createdBy,
            String groupName,
            String createdByName,
            // every group event
            String actorId,
            String actorName,
            // GroupMembersAddedEvent
            List<Member> members,
            String joinedAt,
            // GroupMemberRemovedEvent / GroupRoleChangedEvent
            String userId,
            String userName,
            String reason,
            String leftAt,
            String role,
            String at,
            // GroupUpdatedEvent
            String name,
            Boolean descriptionChanged,
            Boolean onlyAdminsCanSend,
            Boolean onlyAdminsCanEdit) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Member(String userId, String name) {
    }
}
