package com.chatapp.conversation.dto.request;

import com.chatapp.conversation.enums.MuteDuration;
import jakarta.validation.constraints.NotNull;

/** Body of PUT /api/conversations/{id}/mute. */
public record MuteRequest(@NotNull(message = "duration is required") MuteDuration duration) {
}
