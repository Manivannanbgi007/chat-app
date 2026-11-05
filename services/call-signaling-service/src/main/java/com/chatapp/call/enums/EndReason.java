package com.chatapp.call.enums;

/** Why a call reached its final state. */
public enum EndReason {
    /** ENDED: somebody pressed "hang up". */
    HANGUP,
    /** ENDED: one side stopped pinging (closed tab, lost network). */
    CONNECTION_LOST,
    /** MISSED: nobody answered within the ring time-out. */
    NO_ANSWER,
    /** MISSED: the caller hung up before the callee answered. */
    CANCELLED,
    /** REJECTED: the callee said no. */
    DECLINED,
    /** BUSY: the callee was already in another call. */
    BUSY
}
