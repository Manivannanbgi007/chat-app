package com.chatapp.notification.repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.chatapp.notification.entity.Notification;
import com.chatapp.notification.enums.NotificationType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    Page<Notification> findByUserId(UUID userId, Pageable pageable);

    long countByUserIdAndReadFalse(UUID userId);

    /** Always filtered by the owner: somebody else's id looks like "not found". */
    Optional<Notification> findByIdAndUserId(UUID id, UUID userId);

    Optional<Notification> findByUnreadKey(String unreadKey);

    /** Marks all unread notifications of the user as read. Returns how many changed. */
    @Modifying
    @Query("update Notification n set n.read = true, n.unreadKey = null, n.updatedAt = :now "
            + "where n.userId = :userId and n.read = false")
    int markAllRead(@Param("userId") UUID userId, @Param("now") Instant now);

    /**
     * One aggregated "new messages" notification per (user, chat) while unread.
     * First message: a new row (count 1). Next messages: the unique unread_key collides, so MySQL runs the UPDATE part
     * instead: count + 1 (the assignments run left to right, so the body already sees the new count).
     * The database does the counting, so two messages at the same moment are both counted.
     */
    @Modifying
    @Query(value = "INSERT INTO notifications (id, user_id, type, title, body, conversation_id, item_count, is_read, "
            + "unread_key, created_at, updated_at) "
            + "VALUES (:id, :userId, :type, :title, '1 new message', :conversationId, 1, FALSE, :unreadKey, :now, :now) "
            + "ON DUPLICATE KEY UPDATE item_count = item_count + 1, "
            + "body = CONCAT(item_count, ' new messages'), title = :title, updated_at = :now",
            nativeQuery = true)
    int upsertNewMessages(@Param("id") String id, @Param("userId") String userId, @Param("type") String type,
                          @Param("title") String title, @Param("conversationId") String conversationId,
                          @Param("unreadKey") String unreadKey, @Param("now") Instant now);

    /** The unread "new messages" notification of a chat (at most one, guaranteed by the unique key). */
    default Optional<Notification> findUnreadNewMessages(UUID userId, UUID conversationId) {
        return findByUnreadKey(newMessagesKey(userId, conversationId));
    }

    static String newMessagesKey(UUID userId, UUID conversationId) {
        return userId + ":" + NotificationType.NEW_MESSAGES + ":" + conversationId;
    }
}
