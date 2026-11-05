package com.chatapp.message.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** All our own settings from application.yml (prefix "chatapp"), in one typed place. */
@ConfigurationProperties(prefix = "chatapp")
public record AppProperties(
        Security security,
        Internal internal,
        Services services,
        WebSocket websocket,
        Messages messages) {

    public record Security(String jwtSecret) {
    }

    public record Internal(String token) {
    }

    /** Where the services we call live, and how long we wait for them. */
    public record Services(String conversationUrl, String userUrl, String mediaUrl, int connectTimeoutMs, int readTimeoutMs) {
    }

    public record WebSocket(String allowedOrigins, int sessionCheckSeconds) {
    }

    public record Messages(int maxLength, int syncOverlapSeconds) {
    }
}
