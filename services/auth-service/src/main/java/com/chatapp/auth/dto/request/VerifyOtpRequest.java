package com.chatapp.auth.dto.request;

import com.chatapp.auth.enums.OtpPurpose;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record VerifyOtpRequest(
        @NotBlank @Size(max = 254) String identifier,
        @NotBlank @Pattern(regexp = "^\\d{6}$", message = "must be exactly 6 digits") String otp,
        @NotNull OtpPurpose purpose) {
}
