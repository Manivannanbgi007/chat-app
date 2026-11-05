package com.chatapp.media.exception;

/**
 * 400 for one named field (the answer looks like a failed @Valid check:
 * message "Validation failed", data {"file": "is required"}).
 */
public class FieldErrorException extends ApiException {

    private final String field;
    private final String problem;

    public FieldErrorException(String field, String problem) {
        super(org.springframework.http.HttpStatus.BAD_REQUEST, "Validation failed", "VALIDATION");
        this.field = field;
        this.problem = problem;
    }

    public String getField() {
        return field;
    }

    public String getProblem() {
        return problem;
    }
}
