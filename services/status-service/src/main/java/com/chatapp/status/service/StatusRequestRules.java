package com.chatapp.status.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.chatapp.status.dto.request.CreateStatusRequest;
import com.chatapp.status.enums.StatusType;
import com.chatapp.status.exception.FieldValidationException;

/**
 * The rules that look at several fields of a new status together (single-field rules such as "at most 700
 * characters" are annotations on CreateStatusRequest). A broken rule gives the normal 400 "Validation failed" answer
 * with a field -> problem map.
 */
public final class StatusRequestRules {

    /** The background colours of text statuses. White text is readable on all of them (contrast 4.5:1 or more). */
    public static final List<String> BACKGROUND_COLORS = List.of(
            "#0F766E", "#1D4ED8", "#7C3AED", "#BE185D", "#B91C1C", "#C2410C", "#4D7C0F", "#334155");

    private StatusRequestRules() {
    }

    public static void check(CreateStatusRequest request) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (request.type() == StatusType.TEXT) {
            if (request.text() == null || request.text().isBlank()) {
                errors.put("text", "is required for a text status");
            }
            if (request.backgroundColor() == null || !BACKGROUND_COLORS.contains(request.backgroundColor())) {
                errors.put("backgroundColor", "must be one of " + String.join(", ", BACKGROUND_COLORS));
            }
            if (request.mediaId() != null) {
                errors.put("mediaId", "is not allowed for a text status");
            }
            if (request.caption() != null) {
                errors.put("caption", "is not allowed for a text status (use text)");
            }
        } else {
            if (request.mediaId() == null) {
                errors.put("mediaId", "is required for a photo or video status");
            }
            if (request.text() != null) {
                errors.put("text", "is only allowed for a text status (use caption)");
            }
            if (request.backgroundColor() != null) {
                errors.put("backgroundColor", "is only allowed for a text status");
            }
        }
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
    }

    /** An empty or blank caption is the same as no caption. */
    public static String normalizedCaption(String caption) {
        return caption == null || caption.isBlank() ? null : caption.strip();
    }
}
