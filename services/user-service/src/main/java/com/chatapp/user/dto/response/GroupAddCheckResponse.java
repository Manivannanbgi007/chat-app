package com.chatapp.user.dto.response;

import java.util.List;
import java.util.UUID;

/** The users the actor may NOT add to a group. An empty list means: all of them may be added. */
public record GroupAddCheckResponse(List<UUID> notAllowed) {
}
