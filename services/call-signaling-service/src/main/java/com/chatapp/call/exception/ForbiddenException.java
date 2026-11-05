package com.chatapp.call.exception;

import org.springframework.http.HttpStatus;

/** 403: we know who you are, but you may not do this (for example one of you blocked the other). */
public class ForbiddenException extends ApiException {

    public ForbiddenException(String message, String errorCode) {
        super(HttpStatus.FORBIDDEN, message, errorCode);
    }
}
