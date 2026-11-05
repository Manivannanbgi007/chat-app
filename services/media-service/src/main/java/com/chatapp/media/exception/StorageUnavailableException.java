package com.chatapp.media.exception;

import org.springframework.http.HttpStatus;

/** 503: the object storage did not answer or refused the request. */
public class StorageUnavailableException extends ApiException {

    public StorageUnavailableException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, message, "DEPENDENCY_UNAVAILABLE");
    }
}
