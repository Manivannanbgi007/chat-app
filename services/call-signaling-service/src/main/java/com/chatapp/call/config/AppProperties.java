package com.chatapp.call.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Our own settings from application.yml (prefix "chatapp"), in one typed place. */
@ConfigurationProperties(prefix = "chatapp")
public record AppProperties(
        Internal internal,
        Services services,
        Calls calls,
        Turn turn) {

    public record Internal(String token) {
    }

    /** Where user-service lives and how long we wait for it. */
    public record Services(String userUrl, int connectTimeoutMs, int readTimeoutMs) {
    }

    /** Time limits of a call (see API_DOCUMENTATION.md 7.3). */
    public record Calls(int ringTimeoutSeconds, int pingTimeoutSeconds, long timeoutCheckMs) {
    }

    /** The TURN server (Coturn) the browsers use when a direct connection is impossible. */
    public record Turn(String secret, String publicHost, int port, long credentialTtlSeconds) {
    }
}
