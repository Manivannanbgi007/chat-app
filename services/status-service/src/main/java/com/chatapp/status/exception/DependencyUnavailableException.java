package com.chatapp.status.exception;

import org.springframework.http.HttpStatus;

/** 503: another service we need (user-service, media-service) did not answer in time or failed. */
public class DependencyUnavailableException extends ApiException {

    public DependencyUnavailableException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, message, "DEPENDENCY_UNAVAILABLE");
    }
}
