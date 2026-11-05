package com.chatapp.message.dto.response;

import java.util.UUID;

/** What the chat list needs from us: the last message and how many messages are unread. */
public record ConversationSummaryDto(UUID conversationId, long unreadCount, MessageDto lastMessage) {
}
