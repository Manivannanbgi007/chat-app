package com.chatapp.notification.dto.request;

import com.chatapp.notification.enums.DevicePlatform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record DeviceTokenRequest(
        @NotBlank @Size(min = 10, max = 4096, message = "must be 10 to 4096 characters") String token,
        @NotNull DevicePlatform platform) {
}
