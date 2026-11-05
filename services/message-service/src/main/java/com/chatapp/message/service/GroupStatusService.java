package com.chatapp.message.service;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.chatapp.message.entity.ConversationMember;
import com.chatapp.message.entity.Message;
import com.chatapp.message.entity.MessageReceipt;
import com.chatapp.message.enums.ReceiptStatus;
import com.chatapp.message.repository.ConversationMemberRepository;
import com.chatapp.message.repository.MessageReceiptRepository;
import org.springframework.stereotype.Service;

/**
 * The status of a GROUP message as its sender sees it (one tick, two grey ticks, two blue ticks).
 *
 * Recipients of a group message = the members who were in the group when it was sent AND are still members.
 * (Someone who left no longer holds the ticks back; someone who joined later never received it.)
 *  - SENT:      not every recipient has received it yet (or there are no recipients)
 *  - DELIVERED: every recipient has at least one device that received it
 *  - READ:      every recipient has read it (in groups READ is always shown, like WhatsApp)
 *
 * Works on a whole list of messages with two queries (periods and receipts), never one query per message.
 */
@Service
public class GroupStatusService {

    private final ConversationMemberRepository memberRepository;
    private final MessageReceiptRepository receiptRepository;

    public GroupStatusService(ConversationMemberRepository memberRepository, MessageReceiptRepository receiptRepository) {
        this.memberRepository = memberRepository;
        this.receiptRepository = receiptRepository;
    }

    /** Which of these conversations are groups (according to our membership copy). */
    public Set<UUID> groupIds(Collection<UUID> conversationIds) {
        if (conversationIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(memberRepository.findGroupIds(new LinkedHashSet<>(conversationIds)));
    }

    /** Message id -> SENT / DELIVERED / READ, for messages that belong to group conversations. */
    public Map<UUID, String> combinedStatuses(Collection<Message> messages) {
        if (messages.isEmpty()) {
            return Map.of();
        }
        Set<UUID> conversationIds = messages.stream().map(Message::getConversationId).collect(Collectors.toSet());
        Map<UUID, List<ConversationMember>> periodsByConversation = memberRepository.findByConversationIdIn(conversationIds)
                .stream().collect(Collectors.groupingBy(ConversationMember::getConversationId));
        Map<UUID, List<MessageReceipt>> receiptsByMessage = receiptRepository
                .findByMessageIdIn(messages.stream().map(Message::getId).toList()).stream()
                .collect(Collectors.groupingBy(MessageReceipt::getMessageId));

        Map<UUID, String> result = new HashMap<>();
        for (Message message : messages) {
            result.put(message.getId(), combinedStatus(message,
                    periodsByConversation.getOrDefault(message.getConversationId(), List.of()),
                    receiptsByMessage.getOrDefault(message.getId(), List.of())));
        }
        return result;
    }

    /** The recipients of one message (see the class comment), computed from the conversation's periods. */
    public static Set<UUID> recipientsOf(Message message, List<ConversationMember> periods) {
        Set<UUID> stillMembers = periods.stream()
                .filter(ConversationMember::isOpen)
                .map(ConversationMember::getUserId)
                .collect(Collectors.toSet());
        Set<UUID> recipients = new LinkedHashSet<>();
        for (ConversationMember period : periods) {
            UUID userId = period.getUserId();
            if (!userId.equals(message.getSenderId()) && stillMembers.contains(userId)
                    && period.covers(message.getCreatedAt())) {
                recipients.add(userId);
            }
        }
        return recipients;
    }

    /** Pure rule, easy to unit test: the combined status of one message. */
    public static String combinedStatus(Message message, List<ConversationMember> periods, List<MessageReceipt> receipts) {
        Set<UUID> recipients = recipientsOf(message, periods);
        if (recipients.isEmpty()) {
            return "SENT";
        }
        Set<UUID> delivered = new HashSet<>();
        Set<UUID> read = new HashSet<>();
        for (MessageReceipt receipt : receipts) {
            delivered.add(receipt.getRecipientId());          // a READ receipt also means delivered
            if (receipt.getStatus() == ReceiptStatus.READ) {
                read.add(receipt.getRecipientId());
            }
        }
        if (read.containsAll(recipients)) {
            return "READ";
        }
        return delivered.containsAll(recipients) ? "DELIVERED" : "SENT";
    }
}
