package com.chatapp.status.dto.response;

import java.time.Instant;
import java.util.UUID;

import com.chatapp.status.enums.StatusType;

/**
 * One of MY statuses: the same fields as StatusResponse plus viewCount (the viewers I am allowed to see, see
 * ViewerService). A separate record because Java records cannot extend each other.
 */
public record MyStatusResponse(
        UUID id,
        UUID ownerId,
        StatusType type,
        String text,
        String backgroundColor,
        String caption,
        StatusMediaResponse media,
        Instant createdAt,
        Instant expiresAt,
        boolean viewedByMe,
        long viewCount) {

    public static MyStatusResponse of(StatusResponse status, long viewCount) {
        return new MyStatusResponse(status.id(), status.ownerId(), status.type(), status.text(),
                status.backgroundColor(), status.caption(), status.media(), status.createdAt(), status.expiresAt(),
                status.viewedByMe(), viewCount);
    }
}
