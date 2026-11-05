package com.chatapp.conversation.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.chatapp.conversation.entity.Conversation;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    List<Conversation> findByIdIn(Collection<UUID> ids);

    /**
     * Reads the conversation and LOCKS its row until the transaction ends (SELECT ... FOR UPDATE).
     * Every change to a group starts with this, so two admins changing the same group at the same moment
     * run one after the other and always see each other's result (group size limit, last admin, double add).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Conversation c where c.id = :id")
    Optional<Conversation> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Moves lastMessageAt forward, never backwards (so old or repeated events change nothing).
     * Returns how many rows changed (0 or 1).
     */
    @Modifying
    @Query("update Conversation c set c.lastMessageAt = :time "
            + "where c.id = :id and (c.lastMessageAt is null or c.lastMessageAt < :time)")
    int moveLastMessageAtForward(@Param("id") UUID id, @Param("time") Instant time);
}
