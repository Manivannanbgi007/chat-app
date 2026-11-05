package com.chatapp.call.util;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * The one way this service reads the clock for data it stores.
 * MySQL DATETIME(6) keeps microseconds, so the time is cut to microseconds BEFORE it is used: the value in the
 * database, in the answer and in the Kafka event is then exactly the same.
 */
public final class Times {

    private Times() {
    }

    public static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
