package com.chatapp.call.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.chatapp.call.entity.Call;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CallRepository extends JpaRepository<Call, UUID> {

    /**
     * The call, with its row locked until the transaction ends (SELECT ... FOR UPDATE). Every change of a call goes
     * through here, so "accept" and "cancel" arriving at the same moment are handled one after the other.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Call c where c.id = :id")
    Optional<Call> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Like findByIdForUpdate, but if another service instance is working on this call right now we skip it instead of
     * waiting (used by the time-out job, which runs on every instance).
     */
    @Query(value = "SELECT * FROM calls WHERE id = :id FOR UPDATE SKIP LOCKED", nativeQuery = true)
    Optional<Call> findByIdForUpdateSkipLocked(@Param("id") String id);

    /** Every call I made or received, newest first. */
    @Query(value = "select c from Call c where c.callerId = :userId or c.calleeId = :userId order by c.createdAt desc, c.id desc",
            countQuery = "select count(c) from Call c where c.callerId = :userId or c.calleeId = :userId")
    Page<Call> findHistory(@Param("userId") UUID userId, Pageable pageable);

    /** Calls that are still ringing although they started before the cutoff (candidates for "nobody answered"). */
    @Query("select c.id from Call c where c.status = com.chatapp.call.enums.CallStatus.RINGING and c.createdAt < :cutoff "
            + "order by c.createdAt")
    List<UUID> findRingingStartedBefore(@Param("cutoff") Instant cutoff, Pageable pageable);

    /** Accepted calls where one side has not pinged since the cutoff (candidates for "connection lost"). */
    @Query("select c.id from Call c where c.status = com.chatapp.call.enums.CallStatus.ACCEPTED "
            + "and (c.lastCallerPingAt < :cutoff or c.lastCalleePingAt < :cutoff) order by c.createdAt")
    List<UUID> findAcceptedSilentSince(@Param("cutoff") Instant cutoff, Pageable pageable);
}
