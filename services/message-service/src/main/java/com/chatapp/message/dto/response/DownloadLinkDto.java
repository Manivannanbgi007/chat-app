package com.chatapp.message.dto.response;

import java.time.Instant;

/** A signed link straight to the object storage, valid for a few minutes. */
public record DownloadLinkDto(String url, Instant expiresAt) {
}
