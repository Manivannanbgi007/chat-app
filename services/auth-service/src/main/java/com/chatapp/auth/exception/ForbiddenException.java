package com.chatapp.auth.exception;

import org.springframework.http.HttpStatus;

/** 403: we know who you are, but you may not do this (for example the email is not verified yet). */
public class ForbiddenException extends ApiException {

    public ForbiddenException(String message, String errorCode) {
        super(HttpStatus.FORBIDDEN, message, errorCode);
    }
}
