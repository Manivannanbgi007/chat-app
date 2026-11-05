package com.chatapp.message.entity;

import java.time.Instant;
import java.util.UUID;

import com.chatapp.message.enums.MessageType;
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

/** One chat message. Never changed after it is saved (edits come in a later phase). */
@Entity
@Table(name = "messages")
@Getter
@Setter
@NoArgsConstructor
public class Message {

    /** UUIDv7: ids sort by creation time. */
    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "conversation_id", nullable = false)
    private UUID conversationId;

    @Column(name = "sender_id", nullable = false)
    private UUID senderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MessageType type;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    /** Chosen by the client. The same sender + key can only be saved once, so retries are safe. */
    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    /** Set by the server, never by the client. */
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
