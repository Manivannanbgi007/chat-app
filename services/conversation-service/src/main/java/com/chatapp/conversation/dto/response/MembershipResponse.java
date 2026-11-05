package com.chatapp.conversation.dto.response;

import java.time.Instant;

import com.chatapp.conversation.enums.ParticipantRole;

/** The caller's own membership: active = false means "I left or was removed" (the chat is read-only). */
public record MembershipResponse(ParticipantRole role, boolean active, Instant joinedAt, Instant leftAt) {
}
