package com.chatapp.conversation.dto.request;

import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Body of POST /api/conversations/{id}/members. */
public record AddMembersRequest(
        @NotNull(message = "userIds is required")
        @Size(min = 1, max = 50, message = "add 1 to 50 people at a time")
        List<@NotNull(message = "userIds must not contain null") UUID> userIds) {
}
