package com.chatapp.media.exception;

import org.springframework.http.HttpStatus;

/** 415: the file type is not allowed (UNSUPPORTED_TYPE) or the content does not match its claimed type (CONTENT_MISMATCH). */
public class UnsupportedFileException extends ApiException {

    public UnsupportedFileException(String message, String errorCode) {
        super(HttpStatus.UNSUPPORTED_MEDIA_TYPE, message, errorCode);
    }
}
