package com.chatapp.call.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.chatapp.call.client.UserClient;
import com.chatapp.call.dto.response.CallResponse;
import com.chatapp.call.dto.response.PublicProfileResponse;
import com.chatapp.call.entity.Call;
import com.chatapp.call.exception.DependencyUnavailableException;
import org.springframework.stereotype.Service;

/**
 * Turns a stored call into what ONE participant sees: their direction and the other person's profile (asked from
 * user-service with that participant as the viewer, so privacy settings apply).
 *
 * The profile is "nice to have": if user-service cannot be reached we show "Unknown user" instead of failing.
 * Accepting or ending a call must never fail just because a name could not be loaded.
 */
@Service
public class CallViewService {

    private final UserClient userClient;

    public CallViewService(UserClient userClient) {
        this.userClient = userClient;
    }

    /** One call as viewerId sees it. */
    public CallResponse viewFor(Call call, UUID viewerId) {
        UUID other = call.otherUserOf(viewerId);
        return CallResponse.of(call, viewerId, profiles(viewerId, List.of(other)).getOrDefault(other,
                PublicProfileResponse.unknown(other)));
    }

    /** Many calls of one viewer (a history page): one call to user-service for the whole page. */
    public List<CallResponse> viewsFor(List<Call> calls, UUID viewerId) {
        List<UUID> others = calls.stream().map(call -> call.otherUserOf(viewerId)).distinct().toList();
        Map<UUID, PublicProfileResponse> profiles = profiles(viewerId, others);
        return calls.stream()
                .map(call -> {
                    UUID other = call.otherUserOf(viewerId);
                    return CallResponse.of(call, viewerId, profiles.getOrDefault(other, PublicProfileResponse.unknown(other)));
                })
                .toList();
    }

    private Map<UUID, PublicProfileResponse> profiles(UUID viewerId, List<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        try {
            return userClient.findProfiles(viewerId, userIds);
        } catch (DependencyUnavailableException e) {
            return Map.of();     // shown as "Unknown user"; the call itself is not affected
        }
    }
}
