package com.chatapp.message.websocket;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * The security guard of the WebSocket. Every frame from a browser passes through here first.
 *
 *  - CONNECT: needs "Authorization: Bearer <token>" in the STOMP frame (never in the URL). The token must be valid and
 *    its login session must not have been ended. The user id becomes the connection's principal.
 *  - SEND: only to /app/chat.* destinations.
 *  - SUBSCRIBE: only to my own queues (/user/queue/...). Typing arrives on /user/queue/typing too (Phase 4): a topic
 *    per conversation would keep delivering to a member who was removed from a group while still subscribed.
 * Anything else is refused (the client receives an ERROR frame and the connection closes).
 */
@Component
public class StompAuthChannelInterceptor implements ChannelInterceptor {

    /** Names of the keys stored in the connection's session attributes. */
    public static final String ATTR_SESSION_ID = "sid";
    public static final String ATTR_EXPIRES_AT = "exp";

    private static final Logger log = LoggerFactory.getLogger(StompAuthChannelInterceptor.class);
    private static final Set<String> ALLOWED_USER_QUEUES = Set.of(
            "/user/queue/messages", "/user/queue/receipts", "/user/queue/acks", "/user/queue/errors",
            "/user/queue/typing", "/user/queue/notifications",
            // Phase 7: call events published by call-signaling-service through Redis "ws.events"
            "/user/queue/calls");

    private final JwtDecoder jwtDecoder;
    private final StringRedisTemplate redis;

    public StompAuthChannelInterceptor(JwtDecoder jwtDecoder, StringRedisTemplate redis) {
        this.jwtDecoder = jwtDecoder;
        this.redis = redis;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        StompCommand command = accessor == null ? null : accessor.getCommand();
        if (command == null) {
            return message;     // heartbeat
        }

        switch (command) {
            case CONNECT -> authenticate(accessor);
            case SEND -> {
                requireUser(accessor);
                String destination = accessor.getDestination();
                if (destination == null || !destination.startsWith("/app/chat.")) {
                    throw new MessagingException("Destination not allowed");
                }
            }
            case SUBSCRIBE -> {
                requireUser(accessor);
                authorizeSubscription(accessor.getDestination());
            }
            default -> {
                // UNSUBSCRIBE, DISCONNECT, ACK ... need no extra checks
            }
        }
        return message;
    }

    private void authenticate(StompHeaderAccessor accessor) {
        String header = accessor.getFirstNativeHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ") || header.length() <= 7) {
            throw new MessagingException("Authentication required");
        }
        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(header.substring(7).trim());
        } catch (JwtException e) {
            throw new MessagingException("Invalid or expired token");
        }

        String sid = jwt.getClaimAsString("sid");
        UUID userId;
        try {
            userId = UUID.fromString(jwt.getSubject());
            UUID.fromString(sid);
        } catch (RuntimeException e) {
            throw new MessagingException("Invalid token");
        }
        if (isSessionRevoked(sid)) {
            throw new MessagingException("Session ended");
        }

        accessor.setUser(new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
        Map<String, Object> attributes = accessor.getSessionAttributes();
        if (attributes != null) {
            attributes.put(ATTR_SESSION_ID, sid);
            attributes.put(ATTR_EXPIRES_AT, jwt.getExpiresAt());
        }
    }

    private UUID requireUser(StompHeaderAccessor accessor) {
        if (accessor.getUser() == null) {
            throw new MessagingException("Authentication required");
        }
        return UUID.fromString(accessor.getUser().getName());
    }

    private void authorizeSubscription(String destination) {
        if (destination == null) {
            throw new MessagingException("Destination required");
        }
        if (ALLOWED_USER_QUEUES.contains(destination)) {
            return;
        }
        throw new MessagingException("Destination not allowed");
    }

    /** Same Redis key the gateway checks; auth-service writes it when a session ends. */
    public boolean isSessionRevoked(String sessionId) {
        try {
            return Boolean.TRUE.equals(redis.hasKey("auth:revoked:" + sessionId));
        } catch (RuntimeException e) {
            // Redis down: we cannot check. The token itself still expires in at most 15 minutes.
            log.warn("Could not check session revocation in Redis ({})", e.getClass().getSimpleName());
            return false;
        }
    }

    /** Used by tests and the session checker. */
    public static boolean isExpired(Object expiresAt) {
        return expiresAt instanceof Instant instant && instant.isBefore(Instant.now());
    }
}
