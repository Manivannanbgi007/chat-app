package com.chatapp.user.dto.request;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Internal call from another service: "give me these users as this viewer may see them". */
public record SummariesRequest(
        @NotNull(message = "viewerId is required") UUID viewerId,
        @NotNull(message = "userIds is required")
        @Size(max = 100, message = "at most 100 userIds per request")
        List<@NotNull(message = "userIds must not contain null") UUID> userIds) {
}
