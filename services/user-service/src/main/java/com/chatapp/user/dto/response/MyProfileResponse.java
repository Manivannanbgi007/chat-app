package com.chatapp.user.dto.response;

import java.time.Instant;
import java.util.UUID;

import com.chatapp.user.enums.AccountStatus;

/** The caller's own full profile (includes email and phone). */
public record MyProfileResponse(
        UUID id,
        String username,
        String displayName,
        String email,
        String phone,
        String profileImageUrl,
        String about,
        AccountStatus accountStatus,
        Instant createdAt,
        Instant updatedAt) {
}
