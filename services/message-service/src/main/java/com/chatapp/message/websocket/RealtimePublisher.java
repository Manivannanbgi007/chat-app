package com.chatapp.message.websocket;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends live events to browsers. It does not talk to sockets itself: it publishes to a Redis channel,
 * and EVERY message-service instance receives it (RealtimeSubscriber) and pushes to the users that are
 * connected to that instance. That is how one instance can reach a user connected to another instance.
 * Kafka is NOT used here: it is for durable background work, not for live delivery.
 */
@Service
public class RealtimePublisher {

    public static final String REDIS_CHANNEL = "ws.events";

    private static final Logger log = LoggerFactory.getLogger(RealtimePublisher.class);

    /** What travels through Redis: which users, which of their queues, and the event as JSON text. */
    public record RedisEnvelope(List<UUID> userIds, String destination, String json) {
    }

    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;

    public RealtimePublisher(StringRedisTemplate redis, JsonMapper jsonMapper) {
        this.redis = redis;
        this.jsonMapper = jsonMapper;
    }

    /** To the given users, e.g. destination "/queue/messages" (the "/user" prefix is added by Spring). */
    public void toUsers(Collection<UUID> userIds, String destination, RealtimeEvent<?> event) {
        publish(new RedisEnvelope(List.copyOf(userIds), destination, jsonMapper.writeValueAsString(event)));
    }

    /**
     * Runs the action after the surrounding database transaction committed (or right away when there is none),
     * so a client never receives an event for data that was rolled back.
     */
    public static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private void publish(RedisEnvelope envelope) {
        try {
            redis.convertAndSend(REDIS_CHANNEL, jsonMapper.writeValueAsString(envelope));
        } catch (RuntimeException e) {
            // The message is already saved. A missed live event is repaired by sync when the client reconnects.
            log.warn("Could not publish a live event to Redis ({})", e.getClass().getSimpleName());
        }
    }
}
