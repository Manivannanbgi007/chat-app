package com.chatapp.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @NotBlank
        @Size(min = 3, max = 30)
        @Pattern(regexp = "^[A-Za-z0-9_.]+$", message = "may contain only letters, digits, '_' and '.'")
        String username,

        @NotBlank @Email @Size(max = 254)
        String email,

        @Pattern(regexp = "^\\+[1-9]\\d{7,14}$", message = "must be in international format, e.g. +919876543210")
        String phone,

        @NotBlank
        @Size(min = 8, max = 72, message = "must be 8 to 72 characters")
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "must contain at least one letter and one digit")
        String password,

        @NotBlank @Size(min = 1, max = 50)
        String displayName) {
}
