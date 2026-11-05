package com.chatapp.status.client;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.chatapp.status.dto.response.PublicProfileResponse;

/**
 * What we need from user-service (the owner of contacts, blocks, privacy settings and profiles).
 * An interface so tests can replace it. Methods throw DependencyUnavailableException (503) when user-service fails,
 * except readReceiptsEnabled, which fails safe (see there).
 */
public interface UserClient {

    /** Everybody who has viewerId in THEIR contact list, minus anybody blocked either way. */
    List<UUID> contactOwners(UUID viewerId);

    /** Is viewerId in ownerId's contact list, and did either of them block the other? */
    Relation relation(UUID ownerId, UUID viewerId);

    /**
     * Does this user have read receipts on? Fails safe: an unknown user or an unreachable user-service gives false
     * (the view is then simply not shown), instead of failing the whole request.
     */
    boolean readReceiptsEnabled(UUID userId);

    /** Profiles of the given users as the viewer may see them, by id. Unknown users (and users who blocked the viewer) are missing. */
    Map<UUID, PublicProfileResponse> findProfiles(UUID viewerId, Collection<UUID> userIds);

    record Relation(boolean contact, boolean blocked) {
    }
}
