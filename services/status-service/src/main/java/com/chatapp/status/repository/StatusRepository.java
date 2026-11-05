package com.chatapp.status.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.chatapp.status.entity.Status;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Every read for users contains "expiresAt > now": an expired status is gone, even before the job deletes it. */
public interface StatusRepository extends JpaRepository<Status, UUID> {

    Optional<Status> findByOwnerIdAndIdempotencyKey(UUID ownerId, String idempotencyKey);

    @Query("select s from Status s where s.id = :id and s.expiresAt > :now")
    Optional<Status> findActive(@Param("id") UUID id, @Param("now") Instant now);

    /** My active statuses, oldest first. */
    @Query("select s from Status s where s.ownerId = :ownerId and s.expiresAt > :now order by s.createdAt asc, s.id asc")
    List<Status> findActiveByOwner(@Param("ownerId") UUID ownerId, @Param("now") Instant now);

    @Query("select count(s) from Status s where s.ownerId = :ownerId and s.expiresAt > :now")
    long countActiveByOwner(@Param("ownerId") UUID ownerId, @Param("now") Instant now);

    /** The active statuses of several owners (for the feed), oldest first. */
    @Query("select s from Status s where s.ownerId in :ownerIds and s.expiresAt > :now order by s.createdAt asc, s.id asc")
    List<Status> findActiveByOwners(@Param("ownerIds") Collection<UUID> ownerIds, @Param("now") Instant now);

    /**
     * Expired statuses, oldest first, locked for this transaction. SKIP LOCKED: when several instances run the
     * clean-up job at the same moment, each one takes different rows and nobody waits.
     */
    @Query(value = "SELECT * FROM statuses WHERE expires_at <= :now ORDER BY expires_at LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<Status> lockExpired(@Param("now") Instant now, @Param("limit") int limit);

    @Modifying
    @Query(value = "DELETE FROM statuses WHERE id IN (:ids)", nativeQuery = true)
    int deleteByIds(@Param("ids") Collection<String> ids);
}
