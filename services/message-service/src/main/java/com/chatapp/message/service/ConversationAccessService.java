package com.chatapp.message.service;

import java.util.UUID;

import com.chatapp.message.client.ConversationClient;
import com.chatapp.message.client.ConversationClient.ConversationInfo;
import com.chatapp.message.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;

/**
 * The one place that decides "may this user use this conversation?". The answer comes from conversation-service.
 *
 * Two levels:
 *  - requireMember: a CURRENT member. Needed to WRITE (send a message, typing).
 *  - requireReader: a current OR a former member. Enough to READ (history, receipts...), but the queries then only
 *    return messages from the reader's membership periods (see MessageRepository.VISIBLE_TO_USER).
 *
 * An unknown conversation and a conversation the user never belonged to give the SAME answer (404), so nobody can
 * find out which ids exist. Every successful check also refreshes our copy of the membership periods.
 */
@Service
public class ConversationAccessService {

    private final ConversationClient conversationClient;
    private final MembershipService membershipService;

    public ConversationAccessService(ConversationClient conversationClient, MembershipService membershipService) {
        this.conversationClient = conversationClient;
        this.membershipService = membershipService;
    }

    public ConversationInfo requireMember(UUID conversationId, UUID userId) {
        ConversationInfo conversation = find(conversationId);
        if (!conversation.isCurrentMember(userId)) {
            throw notFound();
        }
        membershipService.sync(conversation);
        return conversation;
    }

    public ConversationInfo requireReader(UUID conversationId, UUID userId) {
        ConversationInfo conversation = find(conversationId);
        if (!conversation.hasEverBeenMember(userId)) {
            throw notFound();
        }
        membershipService.sync(conversation);
        return conversation;
    }

    private ConversationInfo find(UUID conversationId) {
        return conversationClient.find(conversationId).orElseThrow(this::notFound);
    }

    private ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("Conversation not found");
    }
}
