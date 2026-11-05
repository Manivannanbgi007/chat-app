package com.chatapp.notification.dto.response;

/** The user's notification switches. messagesEnabled = private chats, groupsEnabled = everything about groups. */
public record PreferencesResponse(boolean pushEnabled, boolean messagesEnabled, boolean groupsEnabled, boolean callsEnabled) {
}
