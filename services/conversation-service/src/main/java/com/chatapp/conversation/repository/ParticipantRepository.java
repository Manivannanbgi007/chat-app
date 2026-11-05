package com.chatapp.conversation.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.chatapp.conversation.entity.ConversationParticipant;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ParticipantRepository extends JpaRepository<ConversationParticipant, UUID> {

    /** Every chat the user is or was in (also groups they left: those stay in their list, read-only). */
    List<ConversationParticipant> findByUserId(UUID userId);

    /** The conversations a user currently takes part in. */
    List<ConversationParticipant> findByUserIdAndActiveTrue(UUID userId);

    /** The user's row in one conversation (active or not). Empty = the user was never in it. */
    Optional<ConversationParticipant> findByConversationIdAndUserId(UUID conversationId, UUID userId);

    /** Everybody who currently takes part in the given conversations. */
    List<ConversationParticipant> findByConversationIdInAndActiveTrue(Collection<UUID> conversationIds);

    List<ConversationParticipant> findByConversationIdAndActiveTrue(UUID conversationId);

    boolean existsByConversationIdAndUserIdAndActiveTrue(UUID conversationId, UUID userId);

    /**
     * All rows of one user, LOCKED until the transaction ends. Used when pinning: two "pin" requests of the same
     * user at the same moment then run one after the other, so the "at most 3 pinned chats" rule holds.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from ConversationParticipant p where p.userId = :userId")
    List<ConversationParticipant> findByUserIdForUpdate(@Param("userId") UUID userId);
}
