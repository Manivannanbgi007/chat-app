package com.chatapp.message.dto.response;

import java.time.Instant;
import java.util.UUID;

/**
 * "Message info" for the sender: how far one recipient got with one message.
 * status: SENT (not received yet) / DELIVERED / READ. Times are the earliest over the recipient's devices.
 */
public record ReceiptDetailDto(UUID userId, String status, Instant deliveredAt, Instant readAt) {
}
