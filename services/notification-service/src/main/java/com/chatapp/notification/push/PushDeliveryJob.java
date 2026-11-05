package com.chatapp.notification.push;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.chatapp.notification.entity.DeliveryLog;
import com.chatapp.notification.entity.DeviceToken;
import com.chatapp.notification.entity.Notification;
import com.chatapp.notification.enums.DeliveryStatus;
import com.chatapp.notification.repository.DeliveryLogRepository;
import com.chatapp.notification.repository.DeviceTokenRepository;
import com.chatapp.notification.repository.NotificationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sends the pushes that are waiting in notification_delivery_logs.
 *
 * A push row is created in the SAME transaction as its notification, so no push is ever lost. Every few seconds this
 * job takes the due rows and calls the PushSender:
 *   success                      -> SENT
 *   exception, tries left        -> FAILED, tried again after 10 s, then 60 s, then 5 min (retry-delays-seconds)
 *   exception, last allowed try  -> DEAD (we give up; the notification itself stays in the history)
 * The rows are locked while they are sent (FOR UPDATE SKIP LOCKED), so several instances can run the job together.
 */
@Component
public class PushDeliveryJob {

    private static final Logger log = LoggerFactory.getLogger(PushDeliveryJob.class);
    private static final int BATCH_SIZE = 50;

    private final DeliveryLogRepository deliveryLogRepository;
    private final NotificationRepository notificationRepository;
    private final DeviceTokenRepository deviceTokenRepository;
    private final PushSender pushSender;
    private final int maxAttempts;
    private final List<Duration> retryDelays;

    public PushDeliveryJob(DeliveryLogRepository deliveryLogRepository, NotificationRepository notificationRepository,
                           DeviceTokenRepository deviceTokenRepository, PushSender pushSender,
                           @Value("${chatapp.push.max-attempts:3}") int maxAttempts,
                           @Value("${chatapp.push.retry-delays-seconds:10,60,300}") List<Integer> retryDelaySeconds) {
        this.deliveryLogRepository = deliveryLogRepository;
        this.notificationRepository = notificationRepository;
        this.deviceTokenRepository = deviceTokenRepository;
        this.pushSender = pushSender;
        this.maxAttempts = maxAttempts;
        this.retryDelays = retryDelaySeconds.stream().map(Duration::ofSeconds).toList();
    }

    @Scheduled(fixedDelayString = "${chatapp.push.poll-ms:5000}")
    @Transactional
    public void sendDue() {
        List<DeliveryLog> due = deliveryLogRepository.lockDue(Instant.now(), BATCH_SIZE);
        for (DeliveryLog delivery : due) {
            deliver(delivery);
        }
    }

    private void deliver(DeliveryLog delivery) {
        Instant now = Instant.now();
        delivery.setUpdatedAt(now);
        Optional<Notification> notification = notificationRepository.findById(delivery.getNotificationId());
        List<DeviceToken> tokens = deviceTokenRepository.findByUserId(delivery.getUserId());
        if (notification.isEmpty() || tokens.isEmpty()) {
            // The user removed every device in the meantime: nothing to do.
            delivery.setStatus(DeliveryStatus.SENT);
            delivery.setLastError("no device tokens");
            return;
        }

        try {
            Notification n = notification.get();
            pushSender.send(new PushSender.PushMessage(delivery.getUserId(),
                    tokens.stream().map(DeviceToken::getToken).toList(), n.getTitle(), n.getBody(),
                    n.getConversationId(), n.getId()));
            delivery.setAttempts(delivery.getAttempts() + 1);
            delivery.setStatus(DeliveryStatus.SENT);
            delivery.setLastError(null);
        } catch (RuntimeException e) {
            int attempts = delivery.getAttempts() + 1;
            delivery.setAttempts(attempts);
            delivery.setLastError(e.getClass().getSimpleName());   // class only: an error text could contain a token
            if (attempts >= maxAttempts) {
                delivery.setStatus(DeliveryStatus.DEAD);
                log.warn("Push {} failed {} times, giving up ({})", delivery.getId(), attempts, e.getClass().getSimpleName());
            } else {
                delivery.setStatus(DeliveryStatus.FAILED);
                delivery.setNextAttemptAt(now.plus(delayAfter(attempts)));
                log.warn("Push {} failed (try {}), will retry ({})", delivery.getId(), attempts, e.getClass().getSimpleName());
            }
        }
    }

    /** The pause after the n-th failed try: delays[n-1], or the last one if the list is shorter. */
    private Duration delayAfter(int failedAttempts) {
        int index = Math.min(failedAttempts - 1, retryDelays.size() - 1);
        return retryDelays.get(index);
    }
}
