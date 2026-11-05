package com.chatapp.message.dto.request;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

/** Wrapper type Boolean (not boolean): Jackson 3 rejects a missing primitive, and we want a clean 400 instead. */
public record TypingRequest(@NotNull UUID conversationId, @NotNull Boolean typing) {
}
