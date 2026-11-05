package com.chatapp.message.dto.request;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/** Used by REST (conversation id is in the path) and by the STOMP frame (/app/chat.read). */
public record ReadRequest(UUID conversationId, @NotNull UUID upToMessageId) {
}
