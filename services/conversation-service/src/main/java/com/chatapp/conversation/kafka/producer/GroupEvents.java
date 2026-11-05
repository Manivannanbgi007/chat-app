package com.chatapp.conversation.kafka.producer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Payloads of the group events on the topic conversation.events (see KAFKA_EVENT_DESIGN.md).
 * Names are display names at the time of the change (null if user-service did not return one); message-service
 * uses them to write the SYSTEM messages ("Asha added Ravi"). The events never contain message text.
 */
public final class GroupEvents {

    public static final String MEMBERS_ADDED = "GroupMembersAddedEvent";
    public static final String MEMBER_REMOVED = "GroupMemberRemovedEvent";
    public static final String ROLE_CHANGED = "GroupRoleChangedEvent";
    public static final String UPDATED = "GroupUpdatedEvent";

    private GroupEvents() {
    }

    public record NamedUser(UUID userId, String name) {
    }

    public record MembersAdded(UUID conversationId, UUID actorId, String actorName,
                               List<NamedUser> members, Instant joinedAt) {
    }

    /** reason: REMOVED (by an admin) or LEFT (the user left; then the actor is the user). */
    public record MemberRemoved(UUID conversationId, UUID actorId, String actorName,
                                UUID userId, String userName, String reason, Instant leftAt) {
    }

    /** actorId null = automatic (the last admin left and this member became admin). */
    public record RoleChanged(UUID conversationId, UUID actorId, String actorName,
                              UUID userId, String userName, String role, Instant at) {
    }

    /** Only what changed is filled: name / onlyAdminsCanSend / onlyAdminsCanEdit are null when unchanged. */
    public record Updated(UUID conversationId, UUID actorId, String actorName, Instant at,
                          String name, boolean descriptionChanged,
                          Boolean onlyAdminsCanSend, Boolean onlyAdminsCanEdit) {
    }
}
