package com.chatapp.message.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * A message as one particular viewer sees it.
 * status: only for messages the viewer SENT (SENT / DELIVERED / READ), otherwise null.
 * myReceipt: only for messages sent TO the viewer (null / DELIVERED / READ).
 * attachment: null for text and SYSTEM messages; for media messages the file's metadata (content is then the caption).
 */
public record MessageDto(
        UUID id,
        UUID conversationId,
        UUID senderId,
        String type,
        String content,
        Instant createdAt,
        String status,
        String myReceipt,
        AttachmentDto attachment) {
}
