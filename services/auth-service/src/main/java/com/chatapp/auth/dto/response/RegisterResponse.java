package com.chatapp.auth.dto.response;

import java.util.UUID;

public record RegisterResponse(UUID userId, String email, boolean verificationRequired) {
}
