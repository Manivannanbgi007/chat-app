package com.chatapp.status.dto.response;

import java.time.Instant;
import java.util.List;

/**
 * Who saw one of my statuses (newest first). viewersHidden = true when I turned read receipts off myself:
 * then nobody is listed and the count is 0 (as in WhatsApp).
 */
public record ViewersResponse(boolean viewersHidden, long viewCount, List<Viewer> viewers) {

    public record Viewer(PublicProfileResponse user, Instant viewedAt) {
    }

    public static ViewersResponse hidden() {
        return new ViewersResponse(true, 0, List.of());
    }
}
