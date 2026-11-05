package com.chatapp.message.entity;

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
 * One MEMBERSHIP PERIOD: "user X was in conversation Y from joinedAt until leftAt (null = still a member)".
 * A user who was removed and added again has two rows. Copied from conversation-service (its internal answers and
 * its Kafka events); it is never the source of truth for "may this user write here?".
 *
 * Used for:
 *  - finding all conversations of a user (unread counts, sync)
 *  - the removed-member rule: a user only sees messages created inside one of their periods
 *  - knowing who the recipients of a group message are (group read receipts)
 */
@Entity
@Table(name = "conversation_members")
@Getter
@Setter
@NoArgsConstructor
public class ConversationMember {

    public static final String PRIVATE = "PRIVATE";
    public static final String GROUP = "GROUP";

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** PRIVATE or GROUP (copied from conversation-service). */
    @Column(name = "conversation_type", nullable = false, length = 20)
    private String conversationType;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Column(name = "left_at")
    private Instant leftAt;

    /** True when a message created at this time belongs to this period (both ends inclusive). */
    public boolean covers(Instant time) {
        return !joinedAt.isAfter(time) && (leftAt == null || !time.isAfter(leftAt));
    }

    public boolean isOpen() {
        return leftAt == null;
    }
}
