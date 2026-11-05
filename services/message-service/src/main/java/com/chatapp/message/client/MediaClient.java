package com.chatapp.message.client;

import java.time.Instant;
import java.util.UUID;

/** Asks media-service (which owns the files) to reserve a file for a message and to sign download links. */
public interface MediaClient {

    /**
     * Marks the file as used by a message. Throws ResourceNotFoundException (ATTACHMENT_NOT_FOUND) when the file is
     * unknown or not uploaded by ownerId, BadRequestException (ATTACHMENT_TYPE_MISMATCH), ConflictException
     * (ATTACHMENT_ALREADY_USED), or ServiceUnavailableException when media-service is down.
     * The same reference again is fine (a retried send).
     */
    MediaInfo attach(UUID mediaId, UUID ownerId, String kind, String reference);

    /** A signed link, variant "original" or "thumbnail". Throws ResourceNotFoundException when there is none. */
    DownloadLink downloadLink(UUID mediaId, UUID requesterId, String variant);

    /** The part of media-service's answer we keep. */
    record MediaInfo(UUID id, String kind, String fileName, String mimeType, long sizeBytes, Integer width,
                     Integer height, Integer durationSeconds, Boolean hasThumbnail) {
    }

    record DownloadLink(String url, Instant expiresAt) {
    }
}
