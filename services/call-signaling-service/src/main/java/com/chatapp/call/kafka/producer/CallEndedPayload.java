package com.chatapp.call.kafka.producer;

import java.time.Instant;
import java.util.UUID;

import com.chatapp.call.entity.Call;
import com.chatapp.call.enums.CallStatus;

/**
 * Data of a CallEndedEvent (topic call.events, key = call id), written when a call reaches its final state.
 * outcome: ANSWERED (the call was accepted, status ENDED), MISSED, REJECTED or BUSY.
 * notification-service uses MISSED and BUSY for the callee's "missed call" notification.
 */
public record CallEndedPayload(
        UUID callId,
        UUID callerId,
        UUID calleeId,
        String type,
        String outcome,
        String endReason,
        Instant createdAt,
        Instant answeredAt,
        Instant endedAt,
        long durationSeconds) {

    public static final String EVENT_TYPE = "CallEndedEvent";

    public static CallEndedPayload of(Call call) {
        String outcome = call.getStatus() == CallStatus.ENDED ? "ANSWERED" : call.getStatus().name();
        return new CallEndedPayload(call.getId(), call.getCallerId(), call.getCalleeId(), call.getType().name(), outcome,
                call.getEndReason() == null ? null : call.getEndReason().name(), call.getCreatedAt(), call.getAnsweredAt(),
                call.getEndedAt(), call.durationSeconds());
    }
}
