package com.chatapp.conversation.dto.response;

import java.util.UUID;

/**
 * What other users may see about someone (same shape as user-service).
 * about / profileImageUrl are null when the user's privacy settings hide them.
 */
public record PublicProfileResponse(
        UUID id,
        String username,
        String displayName,
        String profileImageUrl,
        String about) {

    /** Shown when user-service does not know (or cannot show) a participant. */
    public static PublicProfileResponse unknown(UUID id) {
        return new PublicProfileResponse(id, null, "Unknown user", null, null);
    }
}
