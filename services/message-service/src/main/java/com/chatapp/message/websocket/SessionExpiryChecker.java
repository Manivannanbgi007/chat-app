package com.chatapp.message.websocket;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * A WebSocket stays open much longer than one HTTP request, so the access token that opened it
 * can expire while it is still connected. Every few seconds we close the sockets that
 *  - have an expired token (the client refreshes its token and reconnects), or
 *  - belong to a login session that was ended (logout, "end device", password reset), or
 *  - never sent a CONNECT frame (a half-open socket nobody uses).
 */
@Component
public class SessionExpiryChecker {

    private static final Logger log = LoggerFactory.getLogger(SessionExpiryChecker.class);
    private static final Duration CONNECT_GRACE = Duration.ofSeconds(15);

    private final WebSocketSessionRegistry registry;
    private final StompAuthChannelInterceptor authInterceptor;
    private final SlidingWindowLimiter limiter;

    public SessionExpiryChecker(WebSocketSessionRegistry registry, StompAuthChannelInterceptor authInterceptor,
                                SlidingWindowLimiter limiter) {
        this.registry = registry;
        this.authInterceptor = authInterceptor;
        this.limiter = limiter;
    }

    @Scheduled(fixedDelayString = "#{${chatapp.websocket.session-check-seconds:30} * 1000}")
    public void checkSessions() {
        for (WebSocketSessionRegistry.OpenSocket socket : registry.openSockets()) {
            WebSocketSession session = socket.session();
            Map<String, Object> attributes = session.getAttributes();
            Object expiresAt = attributes.get(StompAuthChannelInterceptor.ATTR_EXPIRES_AT);
            Object sessionId = attributes.get(StompAuthChannelInterceptor.ATTR_SESSION_ID);

            if (expiresAt == null) {
                if (socket.openedAt().plus(CONNECT_GRACE).isBefore(Instant.now())) {
                    close(session, "no CONNECT frame");
                }
            } else if (StompAuthChannelInterceptor.isExpired(expiresAt)) {
                close(session, "token expired");
            } else if (sessionId != null && authInterceptor.isSessionRevoked(sessionId.toString())) {
                close(session, "session ended");
            }
        }
        limiter.evictIdle();
    }

    private void close(WebSocketSession session, String reason) {
        try {
            log.info("Closing WebSocket {}: {}", session.getId(), reason);
            session.close(CloseStatus.POLICY_VIOLATION.withReason(reason));
        } catch (IOException e) {
            log.debug("WebSocket {} was already closed", session.getId());
        }
    }
}
