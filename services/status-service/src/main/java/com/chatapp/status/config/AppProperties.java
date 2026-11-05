package com.chatapp.status.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Our own settings from application.yml (prefix "chatapp"), in one typed place. */
@ConfigurationProperties(prefix = "chatapp")
public record AppProperties(
        Internal internal,
        Services services,
        Status status) {

    public record Internal(String token) {
    }

    /** Where user-service and media-service live, and how long we wait for them. */
    public record Services(String userUrl, String mediaUrl, int connectTimeoutMs, int readTimeoutMs) {
    }

    /** Status rules (see API_DOCUMENTATION.md 7.4). */
    public record Status(long lifetimeSeconds, int maxActive, long cleanupIntervalMs, int cleanupBatchSize) {
    }
}
