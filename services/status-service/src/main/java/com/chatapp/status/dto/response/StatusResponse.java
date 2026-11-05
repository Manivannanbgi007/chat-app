package com.chatapp.status.dto.response;

import java.time.Instant;
import java.util.UUID;

import com.chatapp.status.enums.StatusType;

/** A status as one viewer sees it. viewedByMe is always true for the viewer's own statuses. */
public record StatusResponse(
        UUID id,
        UUID ownerId,
        StatusType type,
        String text,
        String backgroundColor,
        String caption,
        StatusMediaResponse media,
        Instant createdAt,
        Instant expiresAt,
        boolean viewedByMe) {
}
