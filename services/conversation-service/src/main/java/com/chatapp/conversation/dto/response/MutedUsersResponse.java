package com.chatapp.conversation.dto.response;

import java.util.List;
import java.util.UUID;

/** Answer of GET /internal/conversations/{id}/muted-users. */
public record MutedUsersResponse(List<UUID> mutedUserIds) {
}
