package com.chatapp.message.websocket;

import java.time.Instant;
import java.util.UUID;

/**
 * What the browser receives over the WebSocket. Types: MESSAGE_CREATED, RECEIPT_UPDATED,
 * MESSAGE_ACK, ERROR, TYPING (see WEBSOCKET_DESIGN.md).
 */
public record RealtimeEvent<T>(String type, UUID eventId, UUID conversationId, T data, Instant timestamp) {

    public static <T> RealtimeEvent<T> of(String type, UUID conversationId, T data) {
        return new RealtimeEvent<>(type, UUID.randomUUID(), conversationId, data, Instant.now());
    }
}
