package com.chatapp.message.client;

import java.util.UUID;

/** Asks user-service (the source of truth) about blocks and privacy settings. */
public interface UserClient {

    /** True when either user blocked the other. Throws ServiceUnavailableException when the service is down. */
    boolean blockedEitherWay(UUID userA, UUID userB);

    /**
     * Does this user send / show read receipts? Fails safe: if user-service cannot be reached
     * we answer false (the "read" status stays hidden) instead of failing the whole request.
     */
    boolean readReceiptsEnabled(UUID userId);
}
