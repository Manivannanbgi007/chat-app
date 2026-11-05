package com.chatapp.status.dto.response;

import java.time.Instant;

/** A short-lived signed link to the photo / video (or its thumbnail), made by media-service. */
public record MediaUrlResponse(String url, Instant expiresAt) {
}
