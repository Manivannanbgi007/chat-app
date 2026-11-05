package com.chatapp.notification.entity;

import java.time.Instant;
import java.util.UUID;

import com.chatapp.notification.enums.NotificationType;
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
 * One notification of one user. The text is only "who" and "how many", never the content of a message.
 * While it is unread, unreadKey holds a key that is unique in the database (see the migration);
 * reading it sets the key to null.
 */
@Entity
@Table(name = "notifications")
@Getter
@Setter
@NoArgsConstructor
public class Notification {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private NotificationType type;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 500)
    private String body;

    @Column(name = "conversation_id")
    private UUID conversationId;

    /** For NEW_MESSAGES: how many messages this notification stands for. */
    @Column(name = "item_count", nullable = false)
    private int count = 1;

    @Column(name = "is_read", nullable = false)
    private boolean read;

    @Column(name = "unread_key", length = 200)
    private String unreadKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /** Marks it read and frees the unread key, so the next message starts a new notification. */
    public void markRead(Instant now) {
        this.read = true;
        this.unreadKey = null;
        this.updatedAt = now;
    }
}
