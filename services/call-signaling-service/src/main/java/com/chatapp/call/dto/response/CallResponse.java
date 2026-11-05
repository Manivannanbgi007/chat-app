package com.chatapp.call.dto.response;

import java.time.Instant;
import java.util.UUID;

import com.chatapp.call.entity.Call;
import com.chatapp.call.enums.CallDirection;
import com.chatapp.call.enums.CallStatus;
import com.chatapp.call.enums.CallType;
import com.chatapp.call.enums.EndReason;

/**
 * A call as ONE participant sees it (API_DOCUMENTATION.md 7.3): direction and otherUser depend on the viewer.
 * The same shape is used in REST answers and in the live events.
 */
public record CallResponse(
        UUID id,
        CallType type,
        CallStatus status,
        EndReason endReason,
        CallDirection direction,
        UUID callerId,
        UUID calleeId,
        PublicProfileResponse otherUser,
        Instant createdAt,
        Instant answeredAt,
        Instant endedAt,
        long durationSeconds) {

    /** The call seen by viewerId; otherUser is the other participant's profile as the viewer may see it. */
    public static CallResponse of(Call call, UUID viewerId, PublicProfileResponse otherUser) {
        CallDirection direction = call.getCallerId().equals(viewerId) ? CallDirection.OUTGOING : CallDirection.INCOMING;
        return new CallResponse(call.getId(), call.getType(), call.getStatus(), call.getEndReason(), direction,
                call.getCallerId(), call.getCalleeId(), otherUser, call.getCreatedAt(), call.getAnsweredAt(),
                call.getEndedAt(), call.durationSeconds());
    }
}
