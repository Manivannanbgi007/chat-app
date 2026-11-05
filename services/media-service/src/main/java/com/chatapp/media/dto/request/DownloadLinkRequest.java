package com.chatapp.media.dto.request;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Sent by message-service after it checked that requesterId may see the message with this file. */
public record DownloadLinkRequest(
        @NotNull UUID requesterId,
        @NotBlank @Pattern(regexp = "^(original|thumbnail)$", message = "must be original or thumbnail") String variant) {

    public boolean wantsThumbnail() {
        return "thumbnail".equals(variant);
    }
}
