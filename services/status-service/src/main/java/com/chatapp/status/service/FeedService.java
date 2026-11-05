package com.chatapp.status.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.chatapp.status.client.UserClient;
import com.chatapp.status.dto.response.PublicProfileResponse;
import com.chatapp.status.dto.response.StatusGroupResponse;
import com.chatapp.status.dto.response.StatusResponse;
import com.chatapp.status.entity.Status;
import com.chatapp.status.repository.StatusRepository;
import com.chatapp.status.repository.StatusViewRepository;
import com.chatapp.status.util.Times;
import org.springframework.stereotype.Service;

/**
 * The status feed: everybody whose active statuses I may see, grouped by person.
 *
 * Built per request, in a few steps (each one a single query or call for the whole feed):
 *  1. user-service: who has me in their contact list (blocks either way already left out)?
 *  2. which of them have active statuses?
 *  3. whose privacy mode lets me see them?
 *  4. user-service: their profiles (people it does not show me are dropped)
 *  5. which of these statuses have I already viewed?
 * Order: people with something I have not viewed first, then fully viewed people; newest first inside each part.
 */
@Service
public class FeedService {

    /** At most this many ids in one SQL "IN (...)" list. */
    private static final int IN_CHUNK = 500;

    private final UserClient userClient;
    private final StatusRepository statusRepository;
    private final StatusViewRepository viewRepository;
    private final PrivacyService privacyService;

    public FeedService(UserClient userClient, StatusRepository statusRepository, StatusViewRepository viewRepository,
                       PrivacyService privacyService) {
        this.userClient = userClient;
        this.statusRepository = statusRepository;
        this.viewRepository = viewRepository;
        this.privacyService = privacyService;
    }

    public List<StatusGroupResponse> feed(UUID viewerId) {
        // 1. candidates
        Set<UUID> candidates = new LinkedHashSet<>(userClient.contactOwners(viewerId));
        candidates.remove(viewerId);
        if (candidates.isEmpty()) {
            return List.of();
        }

        // 2. their active statuses, grouped by owner (oldest first, as the query returns them)
        Instant now = Times.now();
        Map<UUID, List<Status>> byOwner = new LinkedHashMap<>();
        for (List<UUID> chunk : chunks(candidates)) {
            for (Status status : statusRepository.findActiveByOwners(chunk, now)) {
                byOwner.computeIfAbsent(status.getOwnerId(), id -> new ArrayList<>()).add(status);
            }
        }
        if (byOwner.isEmpty()) {
            return List.of();
        }

        // 3. privacy
        Set<UUID> visibleOwners = privacyService.ownersVisibleTo(viewerId, byOwner.keySet());
        byOwner.keySet().retainAll(visibleOwners);
        if (byOwner.isEmpty()) {
            return List.of();
        }

        // 4. profiles
        Map<UUID, PublicProfileResponse> profiles = userClient.findProfiles(viewerId, byOwner.keySet());
        byOwner.keySet().retainAll(profiles.keySet());
        if (byOwner.isEmpty()) {
            return List.of();
        }

        // 5. what I have seen
        List<UUID> statusIds = byOwner.values().stream().flatMap(List::stream).map(Status::getId).toList();
        Set<UUID> viewed = new HashSet<>();
        for (List<UUID> chunk : chunks(statusIds)) {
            viewed.addAll(viewRepository.findViewedStatusIds(viewerId, chunk));
        }

        List<StatusGroupResponse> groups = new ArrayList<>();
        byOwner.forEach((ownerId, statuses) -> {
            List<StatusResponse> items = statuses.stream()
                    .map(status -> StatusMapper.toResponse(status, viewed.contains(status.getId())))
                    .toList();
            Instant latestAt = statuses.get(statuses.size() - 1).getCreatedAt();
            boolean allViewed = items.stream().allMatch(StatusResponse::viewedByMe);
            groups.add(new StatusGroupResponse(profiles.get(ownerId), items, latestAt, allViewed));
        });
        groups.sort(Comparator.comparing(StatusGroupResponse::allViewed)       // false (something new) first
                .thenComparing(StatusGroupResponse::latestAt, Comparator.reverseOrder()));
        return groups;
    }

    private static List<List<UUID>> chunks(Iterable<UUID> ids) {
        List<List<UUID>> chunks = new ArrayList<>();
        List<UUID> current = new ArrayList<>();
        for (UUID id : ids) {
            current.add(id);
            if (current.size() == IN_CHUNK) {
                chunks.add(current);
                current = new ArrayList<>();
            }
        }
        if (!current.isEmpty()) {
            chunks.add(current);
        }
        return chunks;
    }
}
