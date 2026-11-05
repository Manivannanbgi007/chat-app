package com.chatapp.message.dto.request;

import java.util.UUID;

import com.chatapp.message.enums.MessageType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The sender is NOT part of the request: it always comes from the access token.
 *
 * TEXT:  content is required (1-4000 characters), attachmentId is not allowed.
 * Media: type IMAGE / VIDEO / AUDIO / VOICE / DOCUMENT, attachmentId is required (the id returned by the media upload),
 *        content is an optional caption.
 * The rules that depend on the type are checked in MessageService (the text length limit is configurable too).
 */
public record SendMessageRequest(
        @NotNull UUID conversationId,
        @NotNull MessageType type,
        @Size(max = 4000, message = "must be at most 4000 characters") String content,
        UUID attachmentId,
        @NotBlank
        @Pattern(regexp = "^[A-Za-z0-9_-]{8,64}$", message = "must be 8-64 letters, digits, '_' or '-'")
        String idempotencyKey) {
}
