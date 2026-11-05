package com.chatapp.conversation.util;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * The one way this service reads the clock for data it stores.
 * MySQL DATETIME(6) keeps microseconds, so we cut the time to microseconds BEFORE using it. Then the value in
 * our database, in the Kafka event and in message-service's copy is exactly the same (no rounding differences).
 */
public final class Times {

    /** "Always" for a mute: far enough in the future to never end. */
    public static final Instant FOREVER = Instant.parse("9999-12-31T23:59:59Z");

    private Times() {
    }

    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
