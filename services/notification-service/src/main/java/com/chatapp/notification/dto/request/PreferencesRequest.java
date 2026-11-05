package com.chatapp.notification.dto.request;

import jakarta.validation.constraints.NotNull;

/**
 * All four switches are required (the request replaces the whole setting).
 * Wrapper type Boolean (not boolean): Jackson 3 rejects a missing primitive; we want a clean 400 with the field name.
 */
public record PreferencesRequest(
        @NotNull Boolean pushEnabled,
        @NotNull Boolean messagesEnabled,
        @NotNull Boolean groupsEnabled,
        @NotNull Boolean callsEnabled) {
}
