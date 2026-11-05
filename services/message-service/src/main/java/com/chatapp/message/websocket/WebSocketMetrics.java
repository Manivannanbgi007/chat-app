package com.chatapp.message.websocket;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Publishes the number of open WebSocket connections of this instance as the metric
 * "websocket.sessions.active" (Prometheus name: websocket_sessions_active). Grafana shows it as
 * "Live WebSocket connections", and it is the number to watch when planning how many instances are needed.
 */
@Component
public class WebSocketMetrics {

    public WebSocketMetrics(MeterRegistry registry, WebSocketSessionRegistry sockets) {
        Gauge.builder("websocket.sessions.active", sockets, WebSocketSessionRegistry::count)
                .description("Open WebSocket connections on this instance")
                .register(registry);
    }
}
