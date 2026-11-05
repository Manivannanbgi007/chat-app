package com.chatapp.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
        @NotBlank @Size(max = 254) String identifier,   // email or phone number
        @NotBlank @Size(max = 72) String password,
        @Size(max = 100) String deviceName) {
}
