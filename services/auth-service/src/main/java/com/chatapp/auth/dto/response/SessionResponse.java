package com.chatapp.auth.dto.response;

import java.time.Instant;
import java.util.UUID;

public record SessionResponse(
        UUID id,
        String deviceName,
        String ipAddress,
        Instant createdAt,
        Instant lastActiveAt,
        boolean current) {
}
