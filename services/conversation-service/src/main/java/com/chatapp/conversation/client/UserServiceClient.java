package com.chatapp.conversation.client;

import java.util.List;
import java.util.UUID;

import com.chatapp.conversation.dto.response.PublicProfileResponse;

/**
 * What we need from user-service. An interface so tests can replace it.
 * Every method throws DependencyUnavailableException (503) when user-service fails.
 */
public interface UserServiceClient {

    /** Profiles of the given users as the viewer is allowed to see them. Unknown ids are simply missing. */
    List<PublicProfileResponse> findSummaries(UUID viewerId, List<UUID> userIds);

    /** True when either user has blocked the other. */
    boolean isBlockedEitherWay(UUID userA, UUID userB);

    /**
     * Which of these users may the actor NOT add to a group? (unknown, blocked either way, or their "groupAdd"
     * privacy setting refuses the actor). An empty list means all of them may be added.
     */
    List<UUID> groupAddNotAllowed(UUID actorId, List<UUID> userIds);
}
