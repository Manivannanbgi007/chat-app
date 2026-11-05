package com.chatapp.conversation.entity;

import java.time.Instant;
import java.util.UUID;

import com.chatapp.conversation.enums.ParticipantRole;
import com.chatapp.conversation.util.Times;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

/**
 * "userId takes part in conversationId" - the CURRENT state, one row per user and chat.
 * When a member leaves, the row stays with active = false (so the chat stays in their list, read-only).
 * When they are added again the same row becomes active again. The full history of membership periods is in
 * membership_periods. The row also holds the user's own settings for this chat (mute, archive, pin).
 * The user itself lives in user-service.
 */
@Entity
@Table(name = "conversation_participants")
@Getter
@Setter
@NoArgsConstructor
public class ConversationParticipant {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ParticipantRole role;

    /** Start of the current (or last) membership period. */
    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    /** End of the last membership period; null while the user is a member. */
    @Column(name = "left_at")
    private Instant leftAt;

    @Column(nullable = false)
    private boolean active;

    /** The chat is muted while this time is in the future. */
    @Column(name = "muted_until")
    private Instant mutedUntil;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "pinned_at")
    private Instant pinnedAt;

    public ConversationParticipant(UUID conversationId, UUID userId, ParticipantRole role) {
        this(conversationId, userId, role, Times.now());
    }

    public ConversationParticipant(UUID conversationId, UUID userId, ParticipantRole role, Instant joinedAt) {
        this.conversationId = conversationId;
        this.userId = userId;
        this.role = role;
        this.joinedAt = joinedAt;
        this.active = true;
    }

    public boolean isAdmin() {
        return role == ParticipantRole.ADMIN;
    }

    /** The user left or was removed. */
    public void leave(Instant at) {
        this.active = false;
        this.leftAt = at;
        this.role = ParticipantRole.MEMBER;
    }

    /** The user was added again: a new membership period starts (as a normal member). */
    public void rejoin(Instant at) {
        this.active = true;
        this.leftAt = null;
        this.joinedAt = at;
        this.role = ParticipantRole.MEMBER;
    }
}
