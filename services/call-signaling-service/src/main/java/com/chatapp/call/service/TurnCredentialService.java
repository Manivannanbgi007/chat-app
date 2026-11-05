package com.chatapp.call.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.chatapp.call.config.AppProperties;
import com.chatapp.call.dto.response.IceServerResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Builds the "iceServers" list a browser passes to RTCPeerConnection: our STUN server and our TURN server (Coturn).
 *
 * TURN needs a username and a password. We use the TURN REST scheme that Coturn supports with --use-auth-secret:
 *   username   = "<expiry time in epoch seconds>:<userId>"
 *   credential = base64( HMAC-SHA1( TURN_SECRET, username ) )
 * Coturn knows the same secret, recomputes the HMAC and refuses the username once the expiry time has passed.
 * So no TURN password is stored anywhere, and a leaked one stops working by itself.
 */
@Service
public class TurnCredentialService {

    private static final String HMAC = "HmacSHA1";

    private final AppProperties.Turn turn;
    private final Clock clock;

    @Autowired
    public TurnCredentialService(AppProperties properties) {
        this(properties.turn(), Clock.systemUTC());
    }

    TurnCredentialService(AppProperties.Turn turn, Clock clock) {
        if (turn == null || turn.secret() == null || turn.secret().length() < 16) {
            throw new IllegalStateException("TURN_SECRET must be at least 16 characters long");
        }
        this.turn = turn;
        this.clock = clock;
    }

    /** STUN first (free, finds the public address), then TURN (relay) with a password valid for this user only. */
    public List<IceServerResponse> iceServersFor(UUID userId) {
        String address = turn.publicHost() + ":" + turn.port();
        long expiresAt = Instant.now(clock).getEpochSecond() + turn.credentialTtlSeconds();
        String username = expiresAt + ":" + userId;
        return List.of(
                new IceServerResponse(List.of("stun:" + address), null, null),
                new IceServerResponse(List.of("turn:" + address + "?transport=udp", "turn:" + address + "?transport=tcp"),
                        username, credentialFor(username)));
    }

    /** base64(HMAC-SHA1(secret, username)), exactly what Coturn computes. */
    String credentialFor(String username) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(turn.secret().getBytes(StandardCharsets.UTF_8), HMAC));
            return Base64.getEncoder().encodeToString(mac.doFinal(username.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HMAC-SHA1 is not available", e);
        }
    }
}
