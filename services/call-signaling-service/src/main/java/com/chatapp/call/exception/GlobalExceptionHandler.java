package com.chatapp.call.exception;

import java.util.LinkedHashMap;
import java.util.Map;

import com.chatapp.call.dto.response.ApiResponseDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * One place that turns every error into the standard ApiResponseDTO format.
 * Controllers and services just throw exceptions; they never build error responses themselves.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Errors we throw on purpose. If they carry an errorCode it goes into data as {"errorCode": "..."}. */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiResponseDTO<Map<String, String>>> handleApiException(ApiException ex) {
        Map<String, String> data = ex.getErrorCode() == null ? null : Map.of("errorCode", ex.getErrorCode());
        return build(ex.getStatus(), ex.getMessage(), data);
    }

    /** @Valid failed on a request body: return which fields are wrong. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponseDTO<Map<String, String>>> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return build(HttpStatus.BAD_REQUEST, "Validation failed", fieldErrors);
    }

    /** A rule over several fields failed (same answer format as @Valid). */
    @ExceptionHandler(FieldValidationException.class)
    public ResponseEntity<ApiResponseDTO<Map<String, String>>> handleFieldValidation(FieldValidationException ex) {
        return build(HttpStatus.BAD_REQUEST, "Validation failed", ex.getFieldErrors());
    }

    /** A constraint on a single request parameter failed (for example ?size=500). */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleParameterValidation(HandlerMethodValidationException ex) {
        return build(HttpStatus.BAD_REQUEST, "Invalid request", null);
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class})
    public ResponseEntity<ApiResponseDTO<Void>> handleBadRequest(Exception ex) {
        return build(HttpStatus.BAD_REQUEST, "Invalid request", null);
    }

    /** A unique constraint caught a race (two identical requests at the same moment). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiResponseDTO<Map<String, String>>> handleDataIntegrity(DataIntegrityViolationException ex) {
        log.warn("Data integrity violation: {}", ex.getClass().getSimpleName());
        return build(HttpStatus.CONFLICT, "The request conflicts with existing data", Map.of("errorCode", "CONFLICT"));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleNotFound(NoResourceFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "Resource not found", null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        return build(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed", null);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        return build(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Unsupported media type", null);
    }

    /** Anything unexpected: log the details here, but tell the client nothing internal. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponseDTO<Void>> handleUnexpected(Exception ex) {
        log.error("Unexpected error", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong. Please try again later.", null);
    }

    private <T> ResponseEntity<ApiResponseDTO<T>> build(HttpStatus status, String message, T data) {
        return ResponseEntity.status(status).body(ApiResponseDTO.error(message, data));
    }
}
