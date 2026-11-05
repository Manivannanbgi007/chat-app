package com.chatapp.message.exception;

import org.springframework.http.HttpStatus;

/** 409: the request clashes with existing data (for example the email is already registered). */
public class ConflictException extends ApiException {

    public ConflictException(String message, String errorCode) {
        super(HttpStatus.CONFLICT, message, errorCode);
    }
}
