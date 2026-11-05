package com.chatapp.conversation.dto.response;

import java.util.List;
import java.util.UUID;

/** The group part of a conversation. adminIds is empty for somebody who is no longer a member. */
public record GroupInfoResponse(
        String name,
        String description,
        String imageUrl,
        UUID createdBy,
        boolean onlyAdminsCanSend,
        boolean onlyAdminsCanEdit,
        List<UUID> adminIds) {
}
