package com.chatapp.status.dto.request;

import java.util.UUID;

import com.chatapp.status.enums.StatusType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/statuses. The owner is NOT part of the request: it always comes from the access token.
 * TEXT needs text + backgroundColor; IMAGE / VIDEO need mediaId (+ optional caption). Which fields belong to which
 * type is checked in StatusRequestRules (a rule over several fields).
 */
public record CreateStatusRequest(
        @NotNull StatusType type,
        @Size(max = 700, message = "must be at most 700 characters") String text,
        String backgroundColor,
        UUID mediaId,
        @Size(max = 700, message = "must be at most 700 characters") String caption,
        @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9_-]{8,64}$", message = "must be 8-64 letters, digits, '_' or '-'")
        String idempotencyKey) {
}
