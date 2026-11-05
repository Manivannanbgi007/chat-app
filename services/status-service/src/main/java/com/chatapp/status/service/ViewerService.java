package com.chatapp.status.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.chatapp.status.client.UserClient;
import com.chatapp.status.dto.response.PublicProfileResponse;
import com.chatapp.status.dto.response.ViewersResponse;
import com.chatapp.status.entity.Status;
import com.chatapp.status.entity.StatusView;
import com.chatapp.status.exception.ResourceNotFoundException;
import com.chatapp.status.repository.StatusRepository;
import com.chatapp.status.repository.StatusViewRepository;
import com.chatapp.status.util.Times;
import org.springframework.stereotype.Service;

/**
 * Who saw MY statuses. Read receipts decide, as in WhatsApp:
 *  - a view is always recorded (so the viewer's own ring turns grey),
 *  - but it is only counted and listed when the VIEWER has read receipts on,
 *  - and if I (the owner) turned read receipts off, I see no viewers at all (viewersHidden).
 * Read-receipt settings are asked from user-service, once per person per request; an unknown person or an
 * unreachable user-service counts as "off" (the view stays hidden rather than the request failing).
 */
@Service
public class ViewerService {

    private final StatusRepository statusRepository;
    private final StatusViewRepository viewRepository;
    private final UserClient userClient;

    public ViewerService(StatusRepository statusRepository, StatusViewRepository viewRepository, UserClient userClient) {
        this.statusRepository = statusRepository;
        this.viewRepository = viewRepository;
        this.userClient = userClient;
    }

    /** GET /api/statuses/{id}/viewers: only for my own active status. */
    public ViewersResponse viewers(UUID ownerId, UUID statusId) {
        Status status = statusRepository.findActive(statusId, Times.now())
                .filter(s -> s.getOwnerId().equals(ownerId))
                .orElseThrow(() -> new ResourceNotFoundException("Status not found"));
        if (!userClient.readReceiptsEnabled(ownerId)) {
            return ViewersResponse.hidden();
        }

        ReadReceipts receipts = new ReadReceipts();
        List<StatusView> shown = viewRepository.findByStatusIdOrderByViewedAtDesc(status.getId()).stream()
                .filter(view -> receipts.enabled(view.getViewerId()))
                .toList();
        if (shown.isEmpty()) {
            return new ViewersResponse(false, 0, List.of());
        }
        Map<UUID, PublicProfileResponse> profiles = userClient.findProfiles(ownerId,
                shown.stream().map(StatusView::getViewerId).toList());
        // A viewer user-service no longer shows to me (unknown, or they blocked me) stays in the list as
        // "Unknown user", so the list always matches viewCount.
        List<ViewersResponse.Viewer> viewers = shown.stream()
                .map(view -> new ViewersResponse.Viewer(
                        profiles.getOrDefault(view.getViewerId(), PublicProfileResponse.unknown(view.getViewerId())),
                        view.getViewedAt()))
                .toList();
        return new ViewersResponse(false, viewers.size(), viewers);
    }

    /** The viewCount of each of my statuses (for GET /mine): the same rules as the list above, without profiles. */
    public Map<UUID, Long> viewCounts(UUID ownerId, Collection<Status> statuses) {
        Map<UUID, Long> counts = new HashMap<>();
        statuses.forEach(status -> counts.put(status.getId(), 0L));
        if (statuses.isEmpty() || !userClient.readReceiptsEnabled(ownerId)) {
            return counts;
        }
        ReadReceipts receipts = new ReadReceipts();
        for (StatusView view : viewRepository.findByStatusIdIn(counts.keySet())) {
            if (receipts.enabled(view.getViewerId())) {
                counts.merge(view.getStatusId(), 1L, Long::sum);
            }
        }
        return counts;
    }

    /** Asks user-service at most once per person during one request. */
    private class ReadReceipts {
        private final Map<UUID, Boolean> known = new HashMap<>();

        boolean enabled(UUID userId) {
            return known.computeIfAbsent(userId, userClient::readReceiptsEnabled);
        }
    }
}
