package com.chatapp.notification.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.chatapp.notification.entity.DeliveryLog;
import com.chatapp.notification.enums.DeliveryStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeliveryLogRepository extends JpaRepository<DeliveryLog, UUID> {

    /**
     * The pushes that are due now, oldest first. FOR UPDATE SKIP LOCKED lets several instances run the job at the
     * same time without sending the same push twice.
     */
    @Query(value = "SELECT * FROM notification_delivery_logs WHERE status IN ('PENDING', 'FAILED') "
            + "AND next_attempt_at <= :now ORDER BY next_attempt_at LIMIT :limit FOR UPDATE SKIP LOCKED",
            nativeQuery = true)
    List<DeliveryLog> lockDue(@Param("now") Instant now, @Param("limit") int limit);

    /** True while a push for this notification still waits to be sent (then a new message needs no second push). */
    boolean existsByNotificationIdAndStatusIn(UUID notificationId, Collection<DeliveryStatus> statuses);

    List<DeliveryLog> findByNotificationId(UUID notificationId);
}
