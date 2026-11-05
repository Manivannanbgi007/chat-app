package com.chatapp.notification.push;

import java.util.List;
import java.util.UUID;

/**
 * Sends a push notification to the devices of one user. THE PLACE TO PLUG IN FIREBASE (FCM):
 * write a class that implements this interface and mark it @Primary. Any exception means "failed, try again later"
 * (see PushDeliveryJob for the retries).
 */
public interface PushSender {

    void send(PushMessage message);

    /**
     * What is sent. title/body say who and how many ("Alice", "3 new messages"), never message text.
     * Never log tokens.
     */
    record PushMessage(UUID userId, List<String> tokens, String title, String body, UUID conversationId,
                       UUID notificationId) {
    }
}
