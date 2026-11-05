package com.chatapp.message.config;

import com.chatapp.message.websocket.StompAuthChannelInterceptor;
import com.chatapp.message.websocket.WebSocketSessionRegistry;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;

/**
 * STOMP over WebSocket:
 *   browser connects to  /ws
 *   browser sends to     /app/chat.*      (handled by ChatStompController)
 *   browser listens on   /user/queue/*    (private to the logged-in user: messages, receipts, acks, errors, typing)
 * The "simple broker" lives inside this instance. Reaching users on OTHER instances is done through Redis
 * (RealtimePublisher / RealtimeSubscriber).
 */
@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private static final long HEARTBEAT_MS = 10_000;
    private static final int MAX_FRAME_BYTES = 64 * 1024;

    private final StompAuthChannelInterceptor authInterceptor;
    private final WebSocketSessionRegistry sessionRegistry;
    private final AppProperties properties;

    public WebSocketConfig(StompAuthChannelInterceptor authInterceptor, WebSocketSessionRegistry sessionRegistry,
                           AppProperties properties) {
        this.authInterceptor = authInterceptor;
        this.sessionRegistry = sessionRegistry;
        this.properties = properties;
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Only our own web app may open a WebSocket from a browser page.
        String[] origins = properties.websocket().allowedOrigins().split("\\s*,\\s*");
        registry.addEndpoint("/ws").setAllowedOriginPatterns(origins);
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Not a Spring bean on purpose: a TaskScheduler bean would also take over our @Scheduled jobs.
        ThreadPoolTaskScheduler heartbeatScheduler = new ThreadPoolTaskScheduler();
        heartbeatScheduler.setPoolSize(1);
        heartbeatScheduler.setThreadNamePrefix("ws-heartbeat-");
        heartbeatScheduler.initialize();

        registry.setApplicationDestinationPrefixes("/app");
        registry.setUserDestinationPrefix("/user");
        registry.enableSimpleBroker("/queue")
                .setHeartbeatValue(new long[]{HEARTBEAT_MS, HEARTBEAT_MS})
                .setTaskScheduler(heartbeatScheduler);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setMessageSizeLimit(MAX_FRAME_BYTES)
                .setSendBufferSizeLimit(512 * 1024)
                .setSendTimeLimit(20_000);
        sessionRegistry.registerWith(registration);
    }
}
