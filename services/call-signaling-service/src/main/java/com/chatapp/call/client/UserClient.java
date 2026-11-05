package com.chatapp.call.client;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import com.chatapp.call.dto.response.PublicProfileResponse;

/**
 * What we need from user-service (the owner of profiles and blocks). An interface so tests can replace it.
 * Both methods throw DependencyUnavailableException (503) when user-service fails or does not answer in time.
 */
public interface UserClient {

    /** True when either user has blocked the other. */
    boolean blockedEitherWay(UUID userA, UUID userB);

    /** Profiles of the given users as the viewer may see them, by id. Unknown users are simply missing. */
    Map<UUID, PublicProfileResponse> findProfiles(UUID viewerId, Collection<UUID> userIds);
}
