package com.chatapp.notification.entity;

import java.time.Instant;
import java.util.UUID;

import com.chatapp.notification.enums.DeliveryStatus;
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

/** One push that has to be sent (or was sent). It doubles as the retry queue of the push job. */
@Entity
@Table(name = "notification_delivery_logs")
@Getter
@Setter
@NoArgsConstructor
public class DeliveryLog {

    public static final String CHANNEL_PUSH = "PUSH";

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "notification_id", nullable = false)
    private UUID notificationId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 10)
    private String channel = CHANNEL_PUSH;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DeliveryStatus status;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "last_error", length = 300)
    private String lastError;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
