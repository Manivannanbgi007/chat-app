package com.chatapp.media.dto.request;

import java.util.UUID;

import com.chatapp.media.enums.MediaKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Sent by message-service: "this file is now used by a message".
 * reference = "senderId:idempotencyKey", so sending the same message again attaches the same file again without error.
 * status-service (Phase 8) uses reference = "status:ownerId:idempotencyKey" for status photos and videos.
 */
public record AttachRequest(
        @NotNull UUID ownerId,
        @NotNull MediaKind kind,
        @NotBlank @Size(max = 120) String reference) {
}
