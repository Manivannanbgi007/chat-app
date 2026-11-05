package com.chatapp.user.dto.response;

import java.util.UUID;

/** What other users may see. Never contains email or phone. about / profileImageUrl are null when privacy hides them. */
public record PublicProfileResponse(
        UUID id,
        String username,
        String displayName,
        String profileImageUrl,
        String about) {
}
