package com.chatapp.user.dto.request;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AddContactRequest(
        @NotNull(message = "contactUserId is required")
        UUID contactUserId,

        @Size(max = 50, message = "Nickname must be at most 50 characters")
        String nickname) {
}
