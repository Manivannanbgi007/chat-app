package com.chatapp.call.realtime;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.chatapp.call.dto.request.IceCandidateDto;
import com.chatapp.call.dto.response.CallResponse;
import com.chatapp.call.enums.SignalType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends live call events to the browsers (WEBSOCKET_DESIGN.md section 9).
 *
 * We do not hold any sockets. We publish to the Redis channel "ws.events" (the same channel and envelope that
 * message-service and notification-service use); every message-service instance receives it and pushes it to the
 * sockets of that user it holds, on "/user/queue/calls". Every device of the user receives it.
 *
 * Call this only AFTER the database transaction committed, so a browser never hears about a change that was
 * rolled back. If Redis is down the event is lost; the time-outs and GET /api/calls/active repair that.
 */
@Component
public class CallEventPublisher {

    public static final String REDIS_CHANNEL = "ws.events";
    public static final String DESTINATION = "/queue/calls";

    private static final Logger log = LoggerFactory.getLogger(CallEventPublisher.class);

    /** What travels through Redis: which users, which of their queues, and the event as JSON text. */
    public record Envelope(List<UUID> userIds, String destination, String json) {
    }

    /** The event the browser receives (same outer shape as every chat event). Calls have no conversation id. */
    public record LiveEvent(String type, UUID eventId, UUID conversationId, Object data, Instant timestamp) {
    }

    /** data of CALL_ACCEPTED: the call (as the receiver sees it) and the device that answered. */
    public record AcceptedData(CallResponse call, UUID calleeDeviceId) {
    }

    /** data of CALL_SIGNAL: a WebRTC set-up message from one call device for the other one. */
    public record SignalData(UUID callId, UUID fromUserId, UUID toDeviceId, SignalType signalType, String sdp,
                             List<IceCandidateDto> candidates) {
    }

    private final StringRedisTemplate redis;
    private final JsonMapper jsonMapper;

    public CallEventPublisher(StringRedisTemplate redis, JsonMapper jsonMapper) {
        this.redis = redis;
        this.jsonMapper = jsonMapper;
    }

    /** The callee's devices start ringing. */
    public void incoming(UUID calleeId, CallResponse calleeView) {
        publish(calleeId, "CALL_INCOMING", calleeView);
    }

    /** Sent to each participant with their own view of the call. */
    public void accepted(UUID userId, CallResponse view, UUID calleeDeviceId) {
        publish(userId, "CALL_ACCEPTED", new AcceptedData(view, calleeDeviceId));
    }

    /** The call reached its final state. Sent to each participant with their own view. */
    public void ended(UUID userId, CallResponse view) {
        publish(userId, "CALL_ENDED", view);
    }

    public void signal(UUID toUserId, SignalData data) {
        publish(toUserId, "CALL_SIGNAL", data);
    }

    private void publish(UUID userId, String type, Object data) {
        try {
            LiveEvent event = new LiveEvent(type, UUID.randomUUID(), null, data, Instant.now());
            Envelope envelope = new Envelope(List.of(userId), DESTINATION, jsonMapper.writeValueAsString(event));
            redis.convertAndSend(REDIS_CHANNEL, jsonMapper.writeValueAsString(envelope));
        } catch (RuntimeException e) {
            log.warn("Could not publish a live call event to Redis ({})", e.getClass().getSimpleName());
        }
    }
}
