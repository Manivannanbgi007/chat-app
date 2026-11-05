package com.chatapp.notification.enums;

public enum NotificationType {
    /** Unread messages in one chat (aggregated: one notification per chat while unread). */
    NEW_MESSAGES,
    GROUP_ADDED,
    GROUP_REMOVED,
    GROUP_ADMIN,
    /** A call I did not take (no answer, cancelled by the caller, or I was busy). Phase 7. Never aggregated. */
    MISSED_CALL
}
