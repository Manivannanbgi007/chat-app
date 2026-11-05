package com.chatapp.message.exception;

import org.springframework.http.HttpStatus;

/**
 * Base class for errors we throw on purpose in business code.
 * The GlobalExceptionHandler turns it into a response with the right HTTP status.
 * The optional errorCode (for example EMAIL_TAKEN) lets the frontend react without parsing messages.
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
