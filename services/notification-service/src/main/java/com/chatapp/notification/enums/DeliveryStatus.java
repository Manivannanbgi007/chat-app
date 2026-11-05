package com.chatapp.notification.enums;

/** PENDING: waiting for the first try. FAILED: tried, will be tried again later. SENT: done. DEAD: gave up. */
public enum DeliveryStatus {
    PENDING,
    SENT,
    FAILED,
    DEAD
}
