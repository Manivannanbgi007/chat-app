package com.chatapp.conversation.dto.response;

import java.util.List;
import java.util.UUID;

import com.chatapp.conversation.enums.ConversationType;

/**
 * For other services only (GET /internal/conversations/{id}).
 *  - name: the group name (null for a private chat), used in notifications
 *  - participantIds: the current members
 *  - adminIds, onlyAdminsCanSend: what message-service needs to decide who may send (PRIVATE: [] and false)
 *  - members: EVERY membership period, current and past (message-service uses them to decide which messages
 *    each user may see; someone who was added again has several entries)
 */
public record InternalConversationResponse(
        UUID id,
        ConversationType type,
        String name,
        List<UUID> participantIds,
        List<UUID> adminIds,
        boolean onlyAdminsCanSend,
        List<MemberPeriodResponse> members) {
}
