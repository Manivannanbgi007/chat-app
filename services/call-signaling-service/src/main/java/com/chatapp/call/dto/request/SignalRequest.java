package com.chatapp.call.dto.request;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.chatapp.call.enums.SignalType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of POST /api/calls/{id}/signal: a WebRTC set-up message for the other browser.
 *  - OFFER / ANSWER: the session description (sdp), no candidates
 *  - ICE: 1-50 candidates, no sdp
 * The single-field limits are annotations; the "which fields belong to which type" rule is fieldErrors().
 */
public record SignalRequest(
        @NotNull(message = "type is required (OFFER, ANSWER or ICE)") SignalType type,
        @Size(max = 20000, message = "sdp must be at most 20000 characters") String sdp,
        @Size(max = 50, message = "at most 50 candidates per request")
        List<@NotNull(message = "candidates must not contain null") @Valid IceCandidateDto> candidates) {

    public static final int MAX_SDP_LENGTH = 20000;

    /** Problems of the combination of fields (empty map = fine). Single-field limits are checked by @Valid first. */
    public Map<String, String> fieldErrors() {
        Map<String, String> errors = new LinkedHashMap<>();
        if (type == null) {
            return errors;
        }
        if (type == SignalType.ICE) {
            if (candidates == null || candidates.isEmpty()) {
                errors.put("candidates", "ICE needs 1-50 candidates");
            }
            if (sdp != null) {
                errors.put("sdp", "must be empty for ICE");
            }
        } else {
            if (sdp == null || sdp.isEmpty()) {
                errors.put("sdp", type + " needs sdp (1-" + MAX_SDP_LENGTH + " characters)");
            }
            if (candidates != null) {
                errors.put("candidates", "must be empty for " + type);
            }
        }
        return errors;
    }
}
