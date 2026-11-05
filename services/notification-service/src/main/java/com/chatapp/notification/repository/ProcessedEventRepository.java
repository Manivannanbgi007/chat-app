package com.chatapp.notification.repository;

import java.time.Instant;
import java.util.UUID;

import com.chatapp.notification.entity.ProcessedEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Which Kafka events were already handled. */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

    /** Records the event. Returns 1 the first time and 0 if it was recorded before (a duplicate delivery). */
    @Modifying
    @Query(value = "INSERT IGNORE INTO processed_events (event_id, processed_at) VALUES (:eventId, :now)", nativeQuery = true)
    int insertIfNew(@Param("eventId") String eventId, @Param("now") Instant now);
}
