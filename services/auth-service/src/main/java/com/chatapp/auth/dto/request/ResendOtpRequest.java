package com.chatapp.auth.dto.request;

import com.chatapp.auth.enums.OtpPurpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ResendOtpRequest(
        @NotBlank @Size(max = 254) String identifier,
        @NotNull OtpPurpose purpose) {
}
