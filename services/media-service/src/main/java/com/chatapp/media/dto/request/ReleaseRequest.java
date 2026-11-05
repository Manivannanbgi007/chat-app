package com.chatapp.media.dto.request;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Sent by status-service (Phase 8): "the status that used this file is gone, delete the file".
 * reference must be EXACTLY the one the file was attached with (for statuses "status:ownerId:idempotencyKey"),
 * so a caller can only release files it attached itself, never a file used by a message.
 */
public record ReleaseRequest(
        @NotNull UUID ownerId,
        @NotBlank @Size(max = 120) String reference) {
}
