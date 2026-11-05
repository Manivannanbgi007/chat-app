package com.chatapp.media.exception;

import org.springframework.http.HttpStatus;

/** 413: the file is bigger than the limit for its kind. */
public class PayloadTooLargeException extends ApiException {

    public PayloadTooLargeException(String message) {
        super(HttpStatus.PAYLOAD_TOO_LARGE, message, "FILE_TOO_LARGE");
    }
}
