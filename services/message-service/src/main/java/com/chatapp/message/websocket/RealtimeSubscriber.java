package com.chatapp.message.websocket;

import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Receives every live event from Redis and pushes it to the sockets connected to THIS instance.
 * A user who is not connected here is simply skipped (another instance, or nobody, serves them).
 */
@Component
public class RealtimeSubscriber implements MessageListener {

    private static final Logger log = LoggerFactory.getLogger(RealtimeSubscriber.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final JsonMapper jsonMapper;

    public RealtimeSubscriber(SimpMessagingTemplate messagingTemplate, JsonMapper jsonMapper) {
        this.messagingTemplate = messagingTemplate;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public void onMessage(Message redisMessage, byte[] pattern) {
        try {
            String body = new String(redisMessage.getBody(), StandardCharsets.UTF_8);
            RealtimePublisher.RedisEnvelope envelope = jsonMapper.readValue(body, RealtimePublisher.RedisEnvelope.class);

            // the principal name of a socket is the user id
            envelope.userIds().forEach(userId ->
                    messagingTemplate.convertAndSendToUser(userId.toString(), envelope.destination(), envelope.json()));
        } catch (RuntimeException e) {
            log.warn("Could not deliver a live event ({})", e.getClass().getSimpleName());
        }
    }
}
