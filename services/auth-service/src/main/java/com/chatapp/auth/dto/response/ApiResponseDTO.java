package com.chatapp.auth.dto.response;

import java.time.Instant;

/**
 * The one response format used by every chat-app API.
 * (Each service has its own copy of this class on purpose: services share no code.)
 */
public record ApiResponseDTO<T>(boolean success, String message, T data, Instant timestamp) {

    public static <T> ApiResponseDTO<T> ok(String message, T data) {
        return new ApiResponseDTO<>(true, message, data, Instant.now());
    }

    public static <T> ApiResponseDTO<T> error(String message, T data) {
        return new ApiResponseDTO<>(false, message, data, Instant.now());
    }
}
