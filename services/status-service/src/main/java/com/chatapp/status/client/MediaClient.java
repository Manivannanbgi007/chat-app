package com.chatapp.status.client;

import java.time.Instant;
import java.util.UUID;

/**
 * What we need from media-service (the owner of the files). An interface so tests can replace it.
 * Every method throws DependencyUnavailableException (503) when media-service fails or does not answer in time.
 */
public interface MediaClient {

    /**
     * "This file is now used by a status." Throws ResourceNotFoundException (ATTACHMENT_NOT_FOUND) when the file is
     * unknown or not uploaded by ownerId, BadRequestException (ATTACHMENT_TYPE_MISMATCH) when kind differs, and
     * ConflictException (ATTACHMENT_ALREADY_USED) when another reference already uses it. The same reference again is
     * fine (a retried post).
     */
    MediaInfo attach(UUID mediaId, UUID ownerId, String kind, String reference);

    /** A signed link, variant "original" or "thumbnail". Throws ResourceNotFoundException when there is none. */
    DownloadLink downloadLink(UUID mediaId, UUID requesterId, String variant);

    /**
     * Deletes an attached file, but only when ownerId and reference are exactly the ones it was attached with.
     * Returns normally when the file is gone (deleted now, or already gone / not ours: nothing left to do);
     * throws DependencyUnavailableException when media-service could not do it right now (try again later).
     */
    void release(UUID mediaId, UUID ownerId, String reference);

    /** The part of media-service's answer we keep (a copy, so showing a status needs no call). */
    record MediaInfo(UUID id, String kind, String mimeType, Integer width, Integer height, Integer durationSeconds,
                     Boolean hasThumbnail) {
    }

    record DownloadLink(String url, Instant expiresAt) {
    }
}
