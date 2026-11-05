package com.chatapp.call.repository;

import java.time.Instant;
import java.util.UUID;

import com.chatapp.call.entity.CallSlot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CallSlotRepository extends JpaRepository<CallSlot, UUID> {

    /**
     * Takes the user's slot for this call. Returns 1 when it worked and 0 when the user already holds a slot (= is in
     * another call). INSERT IGNORE turns "duplicate primary key" into "0 rows" instead of an error, and if another
     * transaction is inserting the same user at this very moment, MySQL makes us wait for it first: two calls can
     * never both get the same person.
     */
    @Modifying
    @Query(value = "INSERT IGNORE INTO call_slots (user_id, call_id, created_at) VALUES (:userId, :callId, :createdAt)",
            nativeQuery = true)
    int takeSlot(@Param("userId") String userId, @Param("callId") String callId, @Param("createdAt") Instant createdAt);

    /** Frees both slots of a call (called when it reaches a final state). */
    @Modifying
    @Query("delete from CallSlot s where s.callId = :callId")
    int releaseSlots(@Param("callId") UUID callId);
}
