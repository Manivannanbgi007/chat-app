package com.chatapp.conversation.enums;

import java.time.Duration;
import java.time.Instant;

import com.chatapp.conversation.util.Times;

/** How long a chat is muted (the same three choices WhatsApp offers). */
public enum MuteDuration {
    EIGHT_HOURS,
    ONE_WEEK,
    ALWAYS;

    /** The time the mute ends, counted from now. "Always" is a date far in the future. */
    public Instant until(Instant now) {
        return switch (this) {
            case EIGHT_HOURS -> now.plus(Duration.ofHours(8));
            case ONE_WEEK -> now.plus(Duration.ofDays(7));
            case ALWAYS -> Times.FOREVER;
        };
    }
}
