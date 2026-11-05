package com.chatapp.status.dto.response;

import java.time.Instant;
import java.util.List;

/** One person in the feed: their active statuses (oldest first), the newest posting time, and "have I seen all". */
public record StatusGroupResponse(
        PublicProfileResponse owner,
        List<StatusResponse> statuses,
        Instant latestAt,
        boolean allViewed) {
}
