package com.chatapp.status.dto.request;

import java.util.List;
import java.util.UUID;

import com.chatapp.status.enums.PrivacyMode;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of PUT /api/statuses/privacy. userIds = the excepted (CONTACTS_EXCEPT) or chosen (ONLY_SHARE_WITH) people. */
public record PrivacyRequest(
        @NotNull PrivacyMode mode,
        @Size(max = 500, message = "at most 500 people")
        List<@NotNull(message = "must not contain null") UUID> userIds) {
}
