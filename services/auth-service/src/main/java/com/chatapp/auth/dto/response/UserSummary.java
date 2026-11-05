package com.chatapp.auth.dto.response;

import java.util.UUID;

/** The small piece of user info returned together with the tokens. */
public record UserSummary(UUID id, String username, String displayName, String email) {
}
