package com.chatapp.conversation.dto.request;

import com.chatapp.conversation.enums.ParticipantRole;
import jakarta.validation.constraints.NotNull;

/** Body of PUT /api/conversations/{id}/members/{userId}/role: ADMIN = make admin, MEMBER = dismiss as admin. */
public record ChangeRoleRequest(@NotNull(message = "role is required") ParticipantRole role) {
}
