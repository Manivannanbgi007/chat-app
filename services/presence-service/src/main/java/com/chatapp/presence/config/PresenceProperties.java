package com.chatapp.presence.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Our own settings from application.yml (prefix "chatapp.presence").
 * ttlSeconds: a device counts as online this many seconds after its last heartbeat.
 */
@ConfigurationProperties(prefix = "chatapp.presence")
public record PresenceProperties(int ttlSeconds) {

    public PresenceProperties {
        if (ttlSeconds < 1) {
            throw new IllegalStateException("chatapp.presence.ttl-seconds must be at least 1");
        }
    }
}
