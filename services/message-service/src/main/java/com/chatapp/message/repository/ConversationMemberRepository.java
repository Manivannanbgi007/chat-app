package com.chatapp.message.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.chatapp.message.entity.ConversationMember;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Our copy of the membership periods (see ConversationMember). */
public interface ConversationMemberRepository extends JpaRepository<ConversationMember, UUID> {

    List<ConversationMember> findByConversationId(UUID conversationId);

    List<ConversationMember> findByConversationIdIn(Collection<UUID> conversationIds);

    /** Is the user a CURRENT member (an open period)? */
    boolean existsByConversationIdAndUserIdAndLeftAtIsNull(UUID conversationId, UUID userId);

    /** The current members of a conversation (everyone with an open period). */
    @Query("select distinct cm.userId from ConversationMember cm where cm.conversationId = :conversationId and cm.leftAt is null")
    List<UUID> findCurrentMemberIds(@Param("conversationId") UUID conversationId);

    /**
     * Every conversation the user is or was in (open AND closed periods: a removed member still sees the
     * group in the chat list), the most recently joined first.
     */
    @Query("""
            select cm.conversationId from ConversationMember cm
            where cm.userId = :userId
            group by cm.conversationId
            order by max(cm.joinedAt) desc""")
    List<UUID> findConversationIdsOfUser(@Param("userId") UUID userId, Pageable pageable);

    /** The conversations among these that are groups. */
    @Query("select distinct cm.conversationId from ConversationMember cm "
            + "where cm.conversationId in :conversationIds and cm.conversationType = 'GROUP'")
    List<UUID> findGroupIds(@Param("conversationIds") Collection<UUID> conversationIds);

    /**
     * Adds a period unless the row (conversation, user, joined_at) already exists. "INSERT IGNORE" makes this safe
     * when two requests (or a request and a Kafka event) add the same period at the same moment, and it never
     * re-opens a period that is already closed.
     */
    @Modifying
    @Query(value = "INSERT IGNORE INTO conversation_members (id, conversation_id, user_id, conversation_type, joined_at, left_at) "
            + "VALUES (:id, :conversationId, :userId, :type, :joinedAt, :leftAt)", nativeQuery = true)
    int insertIgnore(@Param("id") String id, @Param("conversationId") String conversationId,
                     @Param("userId") String userId, @Param("type") String type,
                     @Param("joinedAt") Instant joinedAt, @Param("leftAt") Instant leftAt);

    /** The user left (or was removed) at leftAt: closes their open period(s) that started before that time. */
    @Modifying
    @Query("""
            update ConversationMember cm set cm.leftAt = :leftAt
            where cm.conversationId = :conversationId and cm.userId = :userId
              and cm.leftAt is null and cm.joinedAt <= :leftAt""")
    int closeOpenPeriods(@Param("conversationId") UUID conversationId, @Param("userId") UUID userId,
                         @Param("leftAt") Instant leftAt);
}
