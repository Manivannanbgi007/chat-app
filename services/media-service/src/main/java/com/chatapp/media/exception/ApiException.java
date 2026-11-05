package com.chatapp.media.exception;

import org.springframework.http.HttpStatus;

/**
 * Base class for errors we throw on purpose in business code.
 * The GlobalExceptionHandler turns it into a response with the right HTTP status.
 * The optional errorCode is a stable text the frontend can react to (for example ALREADY_EXISTS).
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String errorCode;

    public ApiException(HttpStatus status, String message) {
        this(status, message, null);
    }

    public ApiException(HttpStatus status, String message, String errorCode) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
