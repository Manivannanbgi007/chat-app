package com.chatapp.notification.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A user's switches. A user without a row has everything on (see PreferenceService.defaults). */
@Entity
@Table(name = "notification_preferences")
@Getter
@Setter
@NoArgsConstructor
public class NotificationPreference {

    @Id
    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "push_enabled", nullable = false)
    private boolean pushEnabled = true;

    @Column(name = "messages_enabled", nullable = false)
    private boolean messagesEnabled = true;

    @Column(name = "groups_enabled", nullable = false)
    private boolean groupsEnabled = true;

    @Column(name = "calls_enabled", nullable = false)
    private boolean callsEnabled = true;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public static NotificationPreference defaultsFor(UUID userId) {
        NotificationPreference preference = new NotificationPreference();
        preference.setUserId(userId);
        preference.setUpdatedAt(Instant.now());
        return preference;
    }
}
