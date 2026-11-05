package com.chatapp.conversation.exception;

import org.springframework.http.HttpStatus;

/**
 * 400 for one invalid field that can only be checked in the service (for example a group name that is empty
 * after trimming). The answer has the same shape as a Bean Validation error: data = { field: message }.
 */
public class FieldValidationException extends ApiException {

    private final String field;

    public FieldValidationException(String field, String message) {
        super(HttpStatus.BAD_REQUEST, message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
