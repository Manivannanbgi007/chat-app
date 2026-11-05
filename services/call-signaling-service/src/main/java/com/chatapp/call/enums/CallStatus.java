package com.chatapp.call.enums;

/**
 * RINGING -> ACCEPTED -> ENDED, or from RINGING: REJECTED / MISSED. BUSY is final at once.
 * The last four are "final": a final call never changes again.
 */
public enum CallStatus {
    RINGING,
    ACCEPTED,
    ENDED,
    MISSED,
    REJECTED,
    BUSY;

    public boolean isFinal() {
        return this != RINGING && this != ACCEPTED;
    }
}
