package com.chatapp.presence.dto.response;

import java.util.List;
import java.util.UUID;

/** Answer of the internal lookup: which of the asked users have a live device right now. */
public record OnlineUsersResponse(List<UUID> onlineUserIds) {
}
