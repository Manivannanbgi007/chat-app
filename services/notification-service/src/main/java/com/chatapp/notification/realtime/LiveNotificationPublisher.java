package com.chatapp.notification.realtime;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.chatapp.notification.dto.response.NotificationResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tells the browser of a user "you have a new / changed notification" so the bell updates without polling.
 *
 * We do not talk to sockets ourselves. We publish to the Redis channel "ws.events" (the same channel and the same
 * envelope message-service uses); every message-service instance receives it and pushes it to the sockets of that
 * user that are connected to it, on "/user/queue/notifications". See WEBSOCKET_DESIGN.md.
 * If Redis is down the notification is still saved; the client reads the unread count again when it reconnects.
 */
@Component
public class LiveNotificationPublisher {

    public static final String REDIS_CHANNEL = "ws.events";
    public static final String DESTINATION = "/queue/notifications";

    private static final Logger log = LoggerFactory.getLogger(LiveNotificationPublisher.class);

    /** What travels through Redis: which users, which of their queues, and the event as JSON text. */
    public record Envelope(List<UUID> userIds, String destination, String json) {
    }

    /** The event the browser receives. */
    public record NotificationEvent(String type, UUID eventId, UUID conversationId, NotificationResponse data,
                                    Instant timestamp) {
    }

    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;

    public LiveNotificationPublisher(StringRedisTemplate redis, JsonMapper jsonMapper) {
        this.redis = redis;
        this.jsonMapper = jsonMapper;
    }

    public void publish(UUID userId, NotificationResponse notification) {
        try {
            NotificationEvent event = new NotificationEvent("NOTIFICATION", UUID.randomUUID(),
                    notification.conversationId(), notification, Instant.now());
            Envelope envelope = new Envelope(List.of(userId), DESTINATION, jsonMapper.writeValueAsString(event));
            redis.convertAndSend(REDIS_CHANNEL, jsonMapper.writeValueAsString(envelope));
        } catch (RuntimeException e) {
            log.warn("Could not publish a live notification to Redis ({})", e.getClass().getSimpleName());
        }
    }
}
