package com.chatapp.media.dto.response;

import java.time.Instant;

/** The uploader looking at their own file: metadata plus signed links (valid for a few minutes). */
public record OwnerMediaResponse(MediaResponse media, String url, String thumbnailUrl, Instant expiresAt) {
}
