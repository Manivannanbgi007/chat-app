package com.chatapp.call.dto.request;

import java.util.UUID;

import com.chatapp.call.enums.CallType;
import jakarta.validation.constraints.NotNull;

/** Body of POST /api/calls. The caller is never in the body: it is the token's user. */
public record StartCallRequest(
        @NotNull(message = "calleeId is required") UUID calleeId,
        @NotNull(message = "type is required (AUDIO or VIDEO)") CallType type) {
}
