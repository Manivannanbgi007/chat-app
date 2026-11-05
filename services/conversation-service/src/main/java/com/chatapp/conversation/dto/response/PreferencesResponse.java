package com.chatapp.conversation.dto.response;

import java.time.Instant;

/** The caller's own settings for a chat. Muted while mutedUntil is in the future ("always" = 9999-12-31). */
public record PreferencesResponse(Instant mutedUntil, boolean archived, boolean pinned) {
}
