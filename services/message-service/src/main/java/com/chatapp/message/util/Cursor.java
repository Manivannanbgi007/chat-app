package com.chatapp.message.util;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

import com.chatapp.message.exception.BadRequestException;

/**
 * A position in a conversation: the (createdAt, id) of one message.
 * Clients only see it as an opaque string like "1730000000123456_0192...".
 */
public record Cursor(Instant createdAt, UUID id) {

    /** Used as "the start" when there is no message yet. */
    public static final UUID ZERO_ID = new UUID(0L, 0L);

    public String encode() {
        long micros = ChronoUnit.MICROS.between(Instant.EPOCH, createdAt);
        String raw = micros + "_" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    public static Cursor decode(String value) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
            int split = raw.indexOf('_');
            long micros = Long.parseLong(raw.substring(0, split));
            UUID id = UUID.fromString(raw.substring(split + 1));
            return new Cursor(Instant.EPOCH.plus(micros, ChronoUnit.MICROS), id);
        } catch (RuntimeException e) {
            throw new BadRequestException("Invalid cursor", "INVALID_CURSOR");
        }
    }

    public static Cursor of(com.chatapp.message.entity.Message message) {
        return new Cursor(message.getCreatedAt(), message.getId());
    }
}
