package com.chatapp.conversation.dto.response;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.chatapp.conversation.enums.ConversationType;

/**
 * A conversation as the caller sees it (API_DOCUMENTATION.md section 4.1).
 *  - participants: the current members ([] for a group the caller is no longer in)
 *  - otherUser:    PRIVATE only, the participant who is not the caller (null for groups)
 *  - group:        GROUP only (null for private chats)
 *  - membership / preferences: the CALLER's own membership and settings for this chat
 */
public record ConversationResponse(
        UUID id,
        ConversationType type,
        Instant createdAt,
        Instant lastMessageAt,
        List<PublicProfileResponse> participants,
        PublicProfileResponse otherUser,
        GroupInfoResponse group,
        MembershipResponse membership,
        PreferencesResponse preferences) {
}
