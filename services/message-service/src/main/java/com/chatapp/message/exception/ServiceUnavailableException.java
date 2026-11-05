package com.chatapp.message.exception;

import org.springframework.http.HttpStatus;

/** 503: a service we depend on (conversation-service, user-service) did not answer in time. */
public class ServiceUnavailableException extends ApiException {

    public ServiceUnavailableException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, message, "DEPENDENCY_UNAVAILABLE");
    }
}
