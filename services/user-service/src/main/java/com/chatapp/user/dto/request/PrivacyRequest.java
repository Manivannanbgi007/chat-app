package com.chatapp.user.dto.request;

import com.chatapp.user.enums.Visibility;
import jakarta.validation.constraints.NotNull;

/** Boolean (not boolean) on purpose: a missing field must be rejected, not silently become false. */
public record PrivacyRequest(
        @NotNull(message = "lastSeen is required") Visibility lastSeen,
        @NotNull(message = "profilePhoto is required") Visibility profilePhoto,
        @NotNull(message = "about is required") Visibility about,
        @NotNull(message = "groupAdd is required") Visibility groupAdd,
        @NotNull(message = "readReceipts is required") Boolean readReceipts) {
}
