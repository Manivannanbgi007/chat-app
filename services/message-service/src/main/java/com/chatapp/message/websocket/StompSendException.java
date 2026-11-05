package com.chatapp.message.websocket;

import com.chatapp.message.exception.ApiException;

/**
 * A /app/chat.send frame that was rejected. It carries the client's idempotency key so the ERROR event can say
 * WHICH message failed (the client then marks exactly that one as failed).
 */
public class StompSendException extends RuntimeException {

    private final String idempotencyKey;
    private final ApiException failure;

    public StompSendException(String idempotencyKey, ApiException failure) {
        super(failure.getMessage(), failure);
        this.idempotencyKey = idempotencyKey;
        this.failure = failure;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public ApiException getFailure() {
        return failure;
    }
}
