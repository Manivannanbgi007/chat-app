package com.chatapp.message.exception;

import org.springframework.http.HttpStatus;

/** 429: a limit was hit (OTP attempts, OTP resends). */
public class TooManyRequestsException extends ApiException {

    public TooManyRequestsException(String message, String errorCode) {
        super(HttpStatus.TOO_MANY_REQUESTS, message, errorCode);
    }
}
