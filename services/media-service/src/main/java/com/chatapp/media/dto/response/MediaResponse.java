package com.chatapp.media.dto.response;

import java.time.Instant;
import java.util.UUID;

import com.chatapp.media.enums.MediaKind;

/** What the rest of the system may know about a file (never the storage key). */
public record MediaResponse(
        UUID id,
        MediaKind kind,
        String fileName,
        String mimeType,
        long sizeBytes,
        Integer width,
        Integer height,
        Integer durationSeconds,
        boolean hasThumbnail,
        Instant createdAt) {
}
