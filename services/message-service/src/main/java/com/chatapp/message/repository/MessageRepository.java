package com.chatapp.message.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.chatapp.message.entity.Message;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Messages are always ordered by (createdAt, id). All "cursor" queries use that same pair,
 * and every query is backed by an index, so a page never scans the whole table.
 *
 * THE REMOVED-MEMBER RULE: every query that returns messages to a user contains VISIBLE_TO_USER. A message is only
 * visible to a user when it was created inside one of that user's membership periods (joined <= created <= left).
 * A new group member does not see older messages; a removed member keeps the old ones but sees nothing newer.
 */
public interface MessageRepository extends JpaRepository<Message, UUID> {

    /** "m was created while :userId was a member" (one of their periods covers m.createdAt). */
    String VISIBLE_TO_USER = """
             and exists (select cm.id from ConversationMember cm
                         where cm.conversationId = m.conversationId and cm.userId = :userId
                           and cm.joinedAt <= m.createdAt
                           and (cm.leftAt is null or m.createdAt <= cm.leftAt))
            """;

    /** System messages (group changes) are never unread and never acknowledged. */
    String NOT_SYSTEM = " and m.type <> com.chatapp.message.enums.MessageType.SYSTEM ";

    Optional<Message> findBySenderIdAndIdempotencyKey(UUID senderId, String idempotencyKey);

    /** The message, if the user may see it. */
    @Query("select m from Message m where m.id = :id" + VISIBLE_TO_USER)
    Optional<Message> findVisible(@Param("id") UUID id, @Param("userId") UUID userId);

    /** Of the given messages: the ones sent TO this user (not by them, not system) that the user may see. */
    @Query("select m from Message m where m.id in :ids and m.senderId <> :userId" + NOT_SYSTEM + VISIBLE_TO_USER)
    List<Message> findVisibleIncoming(@Param("ids") Collection<UUID> ids, @Param("userId") UUID userId);

    /** The newest message of a conversation that the user may see (page size 1). */
    @Query("select m from Message m where m.conversationId = :conversationId" + VISIBLE_TO_USER
            + " order by m.createdAt desc, m.id desc")
    List<Message> findLatestVisible(@Param("conversationId") UUID conversationId, @Param("userId") UUID userId,
                                    Pageable pageable);

    /** The newest messages of a conversation, newest first. */
    @Query("select m from Message m where m.conversationId = :conversationId" + VISIBLE_TO_USER
            + " order by m.createdAt desc, m.id desc")
    List<Message> findNewest(@Param("conversationId") UUID conversationId, @Param("userId") UUID userId,
                             Pageable pageable);

    /** Messages older than the cursor (createdAt, id), newest first. */
    @Query("""
            select m from Message m
            where m.conversationId = :conversationId
              and (m.createdAt < :createdAt or (m.createdAt = :createdAt and m.id < :id))
            """ + VISIBLE_TO_USER + " order by m.createdAt desc, m.id desc")
    List<Message> findOlderThan(@Param("conversationId") UUID conversationId, @Param("userId") UUID userId,
                                @Param("createdAt") Instant createdAt, @Param("id") UUID id, Pageable pageable);

    /** Messages newer than the cursor (createdAt, id), oldest first (used to catch up after a reconnect). */
    @Query("""
            select m from Message m
            where m.conversationId = :conversationId
              and (m.createdAt > :createdAt or (m.createdAt = :createdAt and m.id > :id))
            """ + VISIBLE_TO_USER + " order by m.createdAt asc, m.id asc")
    List<Message> findNewerThan(@Param("conversationId") UUID conversationId, @Param("userId") UUID userId,
                                @Param("createdAt") Instant createdAt, @Param("id") UUID id, Pageable pageable);

    /** Messages newer than the cursor in ALL conversations of a user (inside their periods), oldest first. */
    @Query("""
            select m from Message m
            where (m.createdAt > :createdAt or (m.createdAt = :createdAt and m.id > :id))
            """ + VISIBLE_TO_USER + " order by m.createdAt asc, m.id asc")
    List<Message> findForSync(@Param("userId") UUID userId, @Param("createdAt") Instant createdAt,
                              @Param("id") UUID id, Pageable pageable);

    /**
     * Unread = sent by someone else, not a system message, visible to the user, and not yet READ by any device of
     * this user. Returns [conversationId, count].
     */
    @Query("""
            select m.conversationId, count(m) from Message m
            where m.conversationId in :conversationIds
              and m.senderId <> :userId
              and not exists (select r.id from MessageReceipt r
                              where r.messageId = m.id and r.recipientId = :userId
                                and r.status = com.chatapp.message.enums.ReceiptStatus.READ)
            """ + NOT_SYSTEM + VISIBLE_TO_USER + " group by m.conversationId")
    List<Object[]> countUnread(@Param("conversationIds") Collection<UUID> conversationIds, @Param("userId") UUID userId);

    /** Incoming messages up to (and including) the cursor that this user may see and has not read yet, oldest first. */
    @Query("""
            select m from Message m
            where m.conversationId = :conversationId
              and m.senderId <> :userId
              and (m.createdAt < :createdAt or (m.createdAt = :createdAt and m.id <= :id))
              and not exists (select r.id from MessageReceipt r
                              where r.messageId = m.id and r.recipientId = :userId
                                and r.status = com.chatapp.message.enums.ReceiptStatus.READ)
            """ + NOT_SYSTEM + VISIBLE_TO_USER + " order by m.createdAt asc, m.id asc")
    List<Message> findUnreadIncomingUpTo(@Param("conversationId") UUID conversationId, @Param("userId") UUID userId,
                                         @Param("createdAt") Instant createdAt, @Param("id") UUID id, Pageable pageable);
}
