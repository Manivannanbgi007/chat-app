package com.chatapp.notification.push;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The default PushSender: it does NOT really push, it only writes one log line (user id and number of devices;
 * never tokens or text). Replace it with an FCM implementation before relying on push.
 */
@Component
public class LoggingPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(LoggingPushSender.class);

    @PostConstruct
    void warnOnce() {
        log.warn("LoggingPushSender is active: push notifications are NOT really delivered to any device. "
                + "Provide a PushSender bean for Firebase Cloud Messaging to deliver them.");
    }

    @Override
    public void send(PushMessage message) {
        log.info("Push for user {} to {} device(s)", message.userId(), message.tokens().size());
    }
}
