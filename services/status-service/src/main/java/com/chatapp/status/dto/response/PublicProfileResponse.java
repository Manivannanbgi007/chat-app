package com.chatapp.status.dto.response;

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

    /** Shown when user-service does not know (or cannot show, or cannot be reached for) a user. */
    public static PublicProfileResponse unknown(UUID id) {
        return new PublicProfileResponse(id, null, "Unknown user", null, null);
    }
}
