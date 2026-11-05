package com.chatapp.status.service;

import com.chatapp.status.dto.response.StatusMediaResponse;
import com.chatapp.status.dto.response.StatusResponse;
import com.chatapp.status.entity.Status;

/** Entity -> answer. Never exposes the media file id or the idempotency key. */
public final class StatusMapper {

    private StatusMapper() {
    }

    public static StatusResponse toResponse(Status status, boolean viewedByMe) {
        StatusMediaResponse media = status.hasMedia()
                ? new StatusMediaResponse(status.getType().name(), status.getMimeType(), status.getWidth(),
                        status.getHeight(), status.getDurationSeconds(), status.isHasThumbnail())
                : null;
        return new StatusResponse(status.getId(), status.getOwnerId(), status.getType(), status.getText(),
                status.getBackgroundColor(), status.getCaption(), media, status.getCreatedAt(), status.getExpiresAt(),
                viewedByMe);
    }
}
