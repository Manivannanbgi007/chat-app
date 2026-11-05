package com.chatapp.notification.service;

import com.chatapp.notification.entity.NotificationPreference;

/**
 * The small decisions "does this user want this?" and "what does the text say?", in one place without any
 * database or network, so they are easy to read and to test.
 * Rule of thumb for the texts: who and how many, NEVER what was written.
 */
public final class NotificationRules {

    public static final String UNKNOWN_PERSON = "Someone";
    public static final String UNKNOWN_GROUP = "Group chat";

    private NotificationRules() {
    }

    /** New messages: private chats follow "messagesEnabled", groups follow "groupsEnabled". */
    public static boolean wantsMessageNotification(NotificationPreference preference, boolean groupChat) {
        return groupChat ? preference.isGroupsEnabled() : preference.isMessagesEnabled();
    }

    /** Added to / removed from a group, made admin. */
    public static boolean wantsGroupNotification(NotificationPreference preference) {
        return preference.isGroupsEnabled();
    }

    /** Missed calls follow "callsEnabled". */
    public static boolean wantsCallNotification(NotificationPreference preference) {
        return preference.isCallsEnabled();
    }

    /** A push is only sent when the user wants push, has a device registered and is not looking at the app. */
    public static boolean wantsPush(NotificationPreference preference, boolean hasDeviceTokens, boolean online) {
        return preference.isPushEnabled() && hasDeviceTokens && !online;
    }

    /** Title of a "new messages" notification: the person for a private chat, the group name for a group. */
    public static String messageTitle(boolean groupChat, String senderName, String groupName) {
        if (groupChat) {
            return isBlank(groupName) ? UNKNOWN_GROUP : groupName;
        }
        return isBlank(senderName) ? UNKNOWN_PERSON : senderName;
    }

    public static String groupTitle(String groupName) {
        return isBlank(groupName) ? UNKNOWN_GROUP : groupName;
    }

    public static String addedBody(String actorName) {
        return nameOrSomeone(actorName) + " added you";
    }

    public static String removedBody(String actorName) {
        return nameOrSomeone(actorName) + " removed you";
    }

    public static String adminBody() {
        return "You are now an admin";
    }

    /** Body of a missed-call notification: "Missed voice call" (AUDIO) or "Missed video call" (VIDEO). */
    public static String missedCallBody(boolean video) {
        return video ? "Missed video call" : "Missed voice call";
    }

    public static String nameOrSomeone(String name) {
        return isBlank(name) ? UNKNOWN_PERSON : name;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
