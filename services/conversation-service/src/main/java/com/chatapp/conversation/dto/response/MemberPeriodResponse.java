package com.chatapp.conversation.dto.response;

import java.time.Instant;
import java.util.UUID;

/** One membership period of a user. leftAt null = still a member. */
public record MemberPeriodResponse(UUID userId, Instant joinedAt, Instant leftAt) {
}
