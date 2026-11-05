package com.chatapp.user.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(
        @NotBlank(message = "Display name is required")
        @Size(max = 50, message = "Display name must be at most 50 characters")
        String displayName,

        @Size(max = 139, message = "About must be at most 139 characters")
        String about) {
}
