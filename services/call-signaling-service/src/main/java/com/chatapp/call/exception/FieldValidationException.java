package com.chatapp.call.exception;

import java.util.Map;

/**
 * 400 "Validation failed" with a map of field -> problem, exactly like a failed @Valid.
 * Used for rules that look at several fields together (for example "an OFFER needs sdp and no candidates"),
 * which Bean Validation annotations on single fields cannot express.
 */
public class FieldValidationException extends RuntimeException {

    private final Map<String, String> fieldErrors;

    public FieldValidationException(Map<String, String> fieldErrors) {
        super("Validation failed");
        this.fieldErrors = Map.copyOf(fieldErrors);
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }
}
