package com.chatapp.media.dto.response;

import java.time.Instant;

public record LinkResponse(String url, Instant expiresAt) {
}
