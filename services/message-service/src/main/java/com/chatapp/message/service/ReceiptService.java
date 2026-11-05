package com.chatapp.message.service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.chatapp.message.client.ConversationClient.ConversationInfo;
import com.chatapp.message.client.UserClient;
import com.chatapp.message.dto.response.ReceiptDetailDto;
import com.chatapp.message.entity.Message;
import com.chatapp.message.entity.MessageReceipt;
import com.chatapp.message.enums.MessageType;
import com.chatapp.message.enums.ReceiptStatus;
import com.chatapp.message.exception.ResourceNotFoundException;
import com.chatapp.message.kafka.MessageEvents;
import com.chatapp.message.kafka.producer.OutboxService;
import com.chatapp.message.repository.ConversationMemberRepository;
import com.chatapp.message.repository.MessageReceiptRepository;
import com.chatapp.message.repository.MessageRepository;
import com.chatapp.message.websocket.RealtimeEvent;
import com.chatapp.message.websocket.RealtimePublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Delivered and read receipts.
 *
 * Rules:
 *  - Nothing is automatic: a message is DELIVERED only when a recipient's device says so,
 *    and READ only when a recipient says so. READ also counts as delivered.
 *  - Status only moves forward (DELIVERED -> READ), and acknowledging twice changes nothing.
 *  - Only a recipient can acknowledge: not the sender, never a SYSTEM message, and only messages from the
 *    recipient's own membership periods (a removed group member cannot acknowledge newer messages).
 *  - The sender is told live (RECEIPT_UPDATED) what to show now:
 *      private chat: that recipient's status; a READ is shown as DELIVERED when the sender or the recipient
 *      turned read receipts off.
 *      group: the combined status of all recipients (GroupStatusService), only once it is DELIVERED or READ.
 */
@Service
public class ReceiptService {

    private static final int READ_BATCH = 500;
    private static final int MAX_READ_BATCHES = 5;

    private final MessageRepository messageRepository;
    private final MessageReceiptRepository receiptRepository;
    private final ConversationMemberRepository memberRepository;
    private final ConversationAccessService accessService;
    private final GroupStatusService groupStatusService;
    private final UserClient userClient;
    private final OutboxService outboxService;
    private final RealtimePublisher realtimePublisher;
    private final TransactionTemplate transactionTemplate;

    public ReceiptService(MessageRepository messageRepository, MessageReceiptRepository receiptRepository,
                          ConversationMemberRepository memberRepository, ConversationAccessService accessService,
                          GroupStatusService groupStatusService, UserClient userClient, OutboxService outboxService,
                          RealtimePublisher realtimePublisher, TransactionTemplate transactionTemplate) {
        this.messageRepository = messageRepository;
        this.receiptRepository = receiptRepository;
        this.memberRepository = memberRepository;
        this.accessService = accessService;
        this.groupStatusService = groupStatusService;
        this.userClient = userClient;
        this.outboxService = outboxService;
        this.realtimePublisher = realtimePublisher;
        this.transactionTemplate = transactionTemplate;
    }

    // -------------------------------------------------------------- delivered

    /** The device says "I received these messages". Returns how many receipts were newly created. */
    public int markDelivered(UUID userId, UUID deviceId, List<UUID> messageIds) {
        List<Message> messages = messageRepository.findAllById(new LinkedHashSet<>(messageIds));

        // Which conversations may this user read? (asked once per conversation, before the transaction)
        Map<UUID, ConversationInfo> readable = new HashMap<>();
        Set<UUID> refused = new HashSet<>();
        for (Message message : messages) {
            UUID conversationId = message.getConversationId();
            if (message.getSenderId().equals(userId) || readable.containsKey(conversationId) || refused.contains(conversationId)) {
                continue;
            }
            readerOf(conversationId, userId).ifPresentOrElse(
                    info -> readable.put(conversationId, info), () -> refused.add(conversationId));
        }
        List<UUID> candidates = messages.stream()
                .filter(m -> readable.containsKey(m.getConversationId()))
                .map(Message::getId)
                .toList();
        if (candidates.isEmpty()) {
            return 0;
        }
        // not mine, not SYSTEM, and inside one of my membership periods
        List<Message> acknowledgeable = messageRepository.findVisibleIncoming(candidates, userId);

        Integer created = transactionTemplate.execute(status -> {
            List<Message> changed = new ArrayList<>();
            for (Message message : acknowledgeable) {
                if (receiptRepository.findByMessageIdAndRecipientIdAndDeviceId(message.getId(), userId, deviceId).isPresent()) {
                    continue;   // already acknowledged by this device: nothing to do
                }
                Instant now = now();
                MessageReceipt receipt = new MessageReceipt();
                receipt.setMessageId(message.getId());
                receipt.setRecipientId(userId);
                receipt.setDeviceId(deviceId);
                receipt.setStatus(ReceiptStatus.DELIVERED);
                receipt.setDeliveredAt(now);
                receiptRepository.save(receipt);
                changed.add(message);

                outboxService.queue("MessageDeliveredEvent", message.getConversationId(), new MessageEvents.MessageDelivered(
                        message.getId(), message.getConversationId(), message.getSenderId(), userId, deviceId, now));
            }
            Set<UUID> groups = readable.values().stream().filter(ConversationInfo::isGroup)
                    .map(ConversationInfo::id).collect(Collectors.toSet());
            notifySenders(changed, userId, groups, Map.of());
            return changed.size();
        });
        return created == null ? 0 : created;
    }

    // ------------------------------------------------------------------- read

    /** The user opened the conversation: mark everything up to the given message as read. */
    public int markRead(UUID userId, UUID deviceId, UUID conversationId, UUID upToMessageId) {
        ConversationInfo conversation = accessService.requireReader(conversationId, userId);
        Message upTo = messageRepository.findVisible(upToMessageId, userId)
                .filter(m -> m.getConversationId().equals(conversationId))
                .orElseThrow(() -> new ResourceNotFoundException("Message not found"));
        Set<UUID> groups = conversation.isGroup() ? Set.of(conversationId) : Set.of();

        // private chats only: privacy, asked once per sender, outside the transaction (groups always show READ)
        Map<UUID, Boolean> readVisibleToSender = new HashMap<>();
        Boolean recipientAllows = null;

        int total = 0;
        for (int batch = 0; batch < MAX_READ_BATCHES; batch++) {
            List<Message> unread = messageRepository.findUnreadIncomingUpTo(
                    conversationId, userId, upTo.getCreatedAt(), upTo.getId(), PageRequest.of(0, READ_BATCH));
            if (unread.isEmpty()) {
                break;
            }
            if (!conversation.isGroup()) {
                if (recipientAllows == null) {
                    recipientAllows = userClient.readReceiptsEnabled(userId);
                }
                boolean allows = recipientAllows;
                unread.forEach(m -> readVisibleToSender.computeIfAbsent(m.getSenderId(),
                        sender -> allows && userClient.readReceiptsEnabled(sender)));
            }

            Integer changed = transactionTemplate.execute(status -> {
                for (Message message : unread) {
                    markOneRead(message, userId, deviceId);
                }
                notifySenders(unread, userId, groups, readVisibleToSender);
                return unread.size();
            });
            total += changed == null ? 0 : changed;
            if (unread.size() < READ_BATCH) {
                break;
            }
        }
        return total;
    }

    private void markOneRead(Message message, UUID userId, UUID deviceId) {
        Instant now = now();
        Optional<MessageReceipt> existing = receiptRepository
                .findByMessageIdAndRecipientIdAndDeviceId(message.getId(), userId, deviceId);
        MessageReceipt receipt = existing.orElseGet(() -> {
            // read without an earlier "delivered" from this device: READ includes delivered
            MessageReceipt created = new MessageReceipt();
            created.setMessageId(message.getId());
            created.setRecipientId(userId);
            created.setDeviceId(deviceId);
            created.setDeliveredAt(now);
            return created;
        });
        receipt.setStatus(ReceiptStatus.READ);
        receipt.setReadAt(now);
        receiptRepository.save(receipt);

        outboxService.queue("MessageReadEvent", message.getConversationId(), new MessageEvents.MessageRead(
                message.getId(), message.getConversationId(), message.getSenderId(), userId, deviceId, now));
    }

    // --------------------------------------------------------- message info

    /**
     * "Message info" for the sender: one entry per recipient. Only the sender may ask, and only for a message they
     * may still see; everyone else gets 404.
     *  - group: the recipients are the members who were in the group when it was sent and still are
     *  - private chat: the other person; a READ hidden by privacy is shown as DELIVERED without a read time
     */
    public List<ReceiptDetailDto> receiptDetails(UUID userId, UUID messageId) {
        Message message = messageRepository.findById(messageId)
                .filter(m -> m.getSenderId().equals(userId) && m.getType() != MessageType.SYSTEM)
                .orElseThrow(() -> new ResourceNotFoundException("Message not found"));
        ConversationInfo conversation = accessService.requireReader(message.getConversationId(), userId);
        messageRepository.findVisible(messageId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Message not found"));

        Set<UUID> recipients = conversation.isGroup()
                ? GroupStatusService.recipientsOf(message, memberRepository.findByConversationId(message.getConversationId()))
                : conversation.participantIds().stream().filter(id -> !id.equals(userId))
                        .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, List<MessageReceipt>> byRecipient = receiptRepository.findByMessageIdIn(List.of(messageId)).stream()
                .collect(Collectors.groupingBy(MessageReceipt::getRecipientId));

        Boolean senderAllows = null;
        List<ReceiptDetailDto> result = new ArrayList<>();
        for (UUID recipient : recipients) {
            List<MessageReceipt> receipts = byRecipient.getOrDefault(recipient, List.of());
            Instant deliveredAt = receipts.stream().map(MessageReceipt::getDeliveredAt).min(Comparator.naturalOrder()).orElse(null);
            Instant readAt = receipts.stream().filter(r -> r.getStatus() == ReceiptStatus.READ)
                    .map(MessageReceipt::getReadAt).filter(java.util.Objects::nonNull)
                    .min(Comparator.naturalOrder()).orElse(null);
            if (readAt != null && !conversation.isGroup()) {
                if (senderAllows == null) {
                    senderAllows = userClient.readReceiptsEnabled(userId);
                }
                if (!senderAllows || !userClient.readReceiptsEnabled(recipient)) {
                    readAt = null;      // hidden by privacy (private chats only)
                }
            }
            String status = readAt != null ? "READ" : deliveredAt != null ? "DELIVERED" : "SENT";
            result.add(new ReceiptDetailDto(recipient, status, deliveredAt, readAt));
        }
        // READ first, then DELIVERED, then SENT; inside a group by time
        result.sort(Comparator.comparing((ReceiptDetailDto d) -> rank(d.status()))
                .thenComparing(d -> d.readAt() != null ? d.readAt() : d.deliveredAt(), Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(ReceiptDetailDto::userId));
        return result;
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Tells the senders (live) what to show after these messages got a new receipt from recipientId.
     * Runs inside the transaction (so it sees the new receipts); the push itself happens after the commit.
     */
    private void notifySenders(List<Message> changed, UUID recipientId, Set<UUID> groupConversationIds,
                               Map<UUID, Boolean> readVisibleToSender) {
        List<Message> inGroups = changed.stream().filter(m -> groupConversationIds.contains(m.getConversationId())).toList();
        Map<UUID, String> groupStatuses = groupStatusService.combinedStatuses(inGroups);
        for (Message message : changed) {
            String visible;
            if (groupConversationIds.contains(message.getConversationId())) {
                visible = groupStatuses.get(message.getId());
                if (!"DELIVERED".equals(visible) && !"READ".equals(visible)) {
                    continue;     // not every recipient got it yet: the sender's ticks do not change
                }
            } else {
                boolean read = readVisibleToSender.containsKey(message.getSenderId());
                visible = read && readVisibleToSender.get(message.getSenderId()) ? "READ" : "DELIVERED";
            }
            notifySender(message, recipientId, visible);
        }
    }

    /** Live "RECEIPT_UPDATED" to all devices of the sender, after the transaction committed. */
    private void notifySender(Message message, UUID recipientId, String visibleStatus) {
        RealtimeEvent<Map<String, Object>> event = RealtimeEvent.of("RECEIPT_UPDATED", message.getConversationId(), Map.of(
                "messageId", message.getId(),
                "conversationId", message.getConversationId(),
                "userId", recipientId,
                "status", visibleStatus));
        RealtimePublisher.afterCommit(() -> realtimePublisher.toUsers(Set.of(message.getSenderId()), "/queue/receipts", event));
    }

    private Optional<ConversationInfo> readerOf(UUID conversationId, UUID userId) {
        try {
            return Optional.of(accessService.requireReader(conversationId, userId));
        } catch (ResourceNotFoundException e) {
            return Optional.empty();
        }
    }

    private static int rank(String status) {
        return switch (status) {
            case "READ" -> 0;
            case "DELIVERED" -> 1;
            default -> 2;
        };
    }

    private Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
