package com.chatapp.user.dto.response;

import java.time.Instant;
import java.util.UUID;

public record ContactResponse(
        UUID id,
        UUID contactUserId,
        String nickname,
        Instant createdAt,
        PublicProfileResponse user) {
}
