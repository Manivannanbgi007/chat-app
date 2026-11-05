package com.chatapp.message.websocket;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

/**
 * Remembers every open WebSocket of THIS instance, so the SessionExpiryChecker can close the ones whose
 * access token expired or whose login session was ended. (Spring does not give us this list itself.)
 */
@Component
public class WebSocketSessionRegistry implements WebSocketHandlerDecoratorFactory {

    public record OpenSocket(WebSocketSession session, Instant openedAt) {
    }

    private final Map<String, OpenSocket> sockets = new ConcurrentHashMap<>();

    @Override
    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                sockets.put(session.getId(), new OpenSocket(session, Instant.now()));
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                sockets.remove(session.getId());
                super.afterConnectionClosed(session, closeStatus);
            }
        };
    }

    public Collection<OpenSocket> openSockets() {
        return sockets.values();
    }

    public int count() {
        return sockets.size();
    }

    /** Registers this factory with Spring's WebSocket transport (called from WebSocketConfig). */
    public void registerWith(WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(this);
    }
}
