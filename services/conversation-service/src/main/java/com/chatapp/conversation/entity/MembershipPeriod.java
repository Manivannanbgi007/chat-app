package com.chatapp.conversation.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

/**
 * One period in which a user was a member of a conversation: from joinedAt to leftAt (null = still a member).
 * Someone who is removed and later added again has two rows. message-service copies these periods and only shows
 * a user the messages created inside their periods ("removed members cannot see future messages").
 */
@Entity
@Table(name = "membership_periods")
@Getter
@Setter
@NoArgsConstructor
public class MembershipPeriod {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Column(name = "left_at")
    private Instant leftAt;

    public MembershipPeriod(UUID conversationId, UUID userId, Instant joinedAt) {
        this.conversationId = conversationId;
        this.userId = userId;
        this.joinedAt = joinedAt;
    }
}
