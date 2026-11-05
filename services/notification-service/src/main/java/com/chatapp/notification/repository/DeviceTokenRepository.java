package com.chatapp.notification.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.chatapp.notification.entity.DeviceToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, UUID> {

    List<DeviceToken> findByUserId(UUID userId);

    List<DeviceToken> findByUserIdIn(Collection<UUID> userIds);

    /** Removes the token only if it belongs to this user. Returns how many rows were deleted. */
    @Modifying
    @Query("delete from DeviceToken t where t.userId = :userId and t.tokenHash = :tokenHash")
    int deleteByUserAndHash(@Param("userId") UUID userId, @Param("tokenHash") String tokenHash);

    /**
     * Registers a token. If somebody already registered the same token (same browser, another account) it moves to
     * the newest user. One atomic statement, so two requests at the same moment cannot create two rows.
     */
    @Modifying
    @Query(value = "INSERT INTO device_tokens (id, user_id, token, token_hash, platform, created_at, last_seen_at) "
            + "VALUES (:id, :userId, :token, :tokenHash, :platform, :now, :now) "
            + "ON DUPLICATE KEY UPDATE user_id = :userId, platform = :platform, last_seen_at = :now",
            nativeQuery = true)
    int upsert(@Param("id") String id, @Param("userId") String userId, @Param("token") String token,
               @Param("tokenHash") String tokenHash, @Param("platform") String platform, @Param("now") Instant now);
}
