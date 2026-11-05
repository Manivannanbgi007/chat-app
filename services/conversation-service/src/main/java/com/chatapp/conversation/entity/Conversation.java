package com.chatapp.conversation.entity;

import java.time.Instant;
import java.util.UUID;

import com.chatapp.conversation.enums.ConversationType;
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

/** A chat between users. Message text is NOT stored here (message-service owns it). */
@Entity
@Table(name = "conversations")
@Getter
@Setter
@NoArgsConstructor
public class Conversation {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConversationType type;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    /** Time of the newest message; updated from MessageCreatedEvent. Null until the first message. */
    @Column(name = "last_message_at")
    private Instant lastMessageAt;

    public Conversation(ConversationType type, UUID createdBy) {
        this(type, createdBy, Times.now());
    }

    /** createdAt is passed in so every row written by one operation carries the same time. */
    public Conversation(ConversationType type, UUID createdBy, Instant createdAt) {
        this.type = type;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public boolean isGroupChat() {
        return type == ConversationType.GROUP;
    }

    /** The time used to sort the chat list: last message if there is one, otherwise creation. */
    public Instant lastActivityAt() {
        return lastMessageAt != null ? lastMessageAt : createdAt;
    }
}
