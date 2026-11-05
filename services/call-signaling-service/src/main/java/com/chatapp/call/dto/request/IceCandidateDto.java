package com.chatapp.call.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * One ICE candidate exactly as the browser's RTCIceCandidate gives it (a possible network path to this browser).
 * The text may be empty: an empty candidate means "no more candidates".
 */
public record IceCandidateDto(
        @NotNull(message = "candidate is required")
        @Size(max = 1024, message = "candidate must be at most 1024 characters") String candidate,
        @Size(max = 64, message = "sdpMid must be at most 64 characters") String sdpMid,
        @Min(value = 0, message = "sdpMLineIndex must be 0-64")
        @Max(value = 64, message = "sdpMLineIndex must be 0-64") Integer sdpMLineIndex) {
}
