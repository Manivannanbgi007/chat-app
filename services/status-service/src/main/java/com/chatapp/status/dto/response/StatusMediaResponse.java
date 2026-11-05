package com.chatapp.status.dto.response;

/** What we know about the photo or video of a status (a copy of media-service's metadata; never the file itself). */
public record StatusMediaResponse(
        String kind,
        String mimeType,
        Integer width,
        Integer height,
        Integer durationSeconds,
        boolean hasThumbnail) {
}
