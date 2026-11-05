package com.chatapp.user.dto.request;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Internal call from conversation-service: "may actorId add these users to a group?" */
public record GroupAddCheckRequest(
        @NotNull(message = "actorId is required") UUID actorId,
        @NotNull(message = "userIds is required")
        @Size(min = 1, max = 256, message = "between 1 and 256 userIds per request")
        List<@NotNull(message = "userIds must not contain null") UUID> userIds) {
}
