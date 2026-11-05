package com.chatapp.notification.dto.response;

import java.time.Instant;
import java.util.UUID;

import com.chatapp.notification.enums.NotificationType;

/** A notification as the client sees it. The text says who and how many, never what was written. */
public record NotificationResponse(
        UUID id,
        NotificationType type,
        String title,
        String body,
        UUID conversationId,
        int count,
        boolean read,
        Instant createdAt,
        Instant updatedAt) {
}
