package com.chatapp.notification.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

import com.chatapp.notification.dto.request.DeviceTokenRequest;
import com.chatapp.notification.repository.DeviceTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Push tokens of the user's browsers and phones. */
@Service
public class DeviceTokenService {

    private final DeviceTokenRepository repository;

    public DeviceTokenService(DeviceTokenRepository repository) {
        this.repository = repository;
    }

    /** Registers the token for this user. A token that belonged to somebody else moves to this user. */
    @Transactional
    public void register(UUID userId, DeviceTokenRequest request) {
        repository.upsert(UUID.randomUUID().toString(), userId.toString(), request.token(), hash(request.token()),
                request.platform().name(), Instant.now());
    }

    /** Removes the token if it is mine (somebody else's token is left alone and nothing is said about it). */
    @Transactional
    public void remove(UUID userId, String token) {
        repository.deleteByUserAndHash(userId, hash(token));
    }

    static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
