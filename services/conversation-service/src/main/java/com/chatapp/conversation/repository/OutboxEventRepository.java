package com.chatapp.conversation.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.chatapp.conversation.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * Oldest unpublished events first. FOR UPDATE SKIP LOCKED lets several service
     * instances run the publisher at the same time without sending the same event twice.
     */
    @Query(value = "SELECT * FROM outbox_events WHERE published = FALSE ORDER BY created_at LIMIT 100 FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<OutboxEvent> lockNextBatch();

    @Modifying
    @Query("delete from OutboxEvent e where e.published = true and e.publishedAt < :before")
    int deletePublishedBefore(@Param("before") Instant before);
}
