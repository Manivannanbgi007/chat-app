package com.chatapp.user.dto.request;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/** Internal call from presence-service: "may viewerId see the online status / last seen of targetId?" */
public record PresenceVisibilityRequest(
        @NotNull(message = "viewerId is required") UUID viewerId,
        @NotNull(message = "targetId is required") UUID targetId) {
}
