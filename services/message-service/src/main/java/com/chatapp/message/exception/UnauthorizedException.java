package com.chatapp.message.exception;

import org.springframework.http.HttpStatus;

/** 401: wrong credentials or an invalid token. */
public class UnauthorizedException extends ApiException {

    public UnauthorizedException(String message, String errorCode) {
        super(HttpStatus.UNAUTHORIZED, message, errorCode);
    }
}
