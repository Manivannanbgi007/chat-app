package com.chatapp.user.dto.response;

import java.util.List;
import java.util.UUID;

/** The users who saved the viewer as a contact (blocks either way already removed). */
public record ContactOwnersResponse(List<UUID> ownerIds) {
}
