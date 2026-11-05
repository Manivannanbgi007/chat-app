package com.chatapp.message.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.chatapp.message.client.UserClient;
import com.chatapp.message.dto.response.AttachmentDto;
import com.chatapp.message.dto.response.MessageDto;
import com.chatapp.message.entity.Message;
import com.chatapp.message.entity.MessageAttachment;
import com.chatapp.message.entity.MessageReceipt;
import com.chatapp.message.enums.MessageType;
import com.chatapp.message.enums.ReceiptStatus;
import com.chatapp.message.repository.MessageAttachmentRepository;
import com.chatapp.message.repository.MessageReceiptRepository;
import org.springframework.stereotype.Service;

/**
 * Turns saved messages into what ONE viewer should see: delivery status for their own messages,
 * and their own receipt state for messages sent to them.
 *  - private chat: the status of the other person (READ only if both allow read receipts)
 *  - group: the combined status of all recipients (GroupStatusService)
 *  - SYSTEM messages (group changes): no status and no receipt
 */
@Service
public class MessageViewService {

    private final MessageReceiptRepository receiptRepository;
    private final MessageAttachmentRepository attachmentRepository;
    private final UserClient userClient;
    private final GroupStatusService groupStatusService;

    public MessageViewService(MessageReceiptRepository receiptRepository, MessageAttachmentRepository attachmentRepository,
                              UserClient userClient, GroupStatusService groupStatusService) {
        this.receiptRepository = receiptRepository;
        this.attachmentRepository = attachmentRepository;
        this.userClient = userClient;
        this.groupStatusService = groupStatusService;
    }

    public List<MessageDto> toDtos(Collection<Message> messages, UUID viewerId) {
        if (messages.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = messages.stream().map(Message::getId).toList();
        Map<UUID, List<MessageReceipt>> receiptsByMessage = receiptRepository.findByMessageIdIn(ids).stream()
                .collect(Collectors.groupingBy(MessageReceipt::getMessageId));
        ReadVisibility visibility = new ReadVisibility(viewerId);
        // one query for the attachments of the whole page (the message id is the attachment's primary key)
        Map<UUID, MessageAttachment> attachments = attachmentRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(MessageAttachment::getMessageId, a -> a));

        // The viewer's own messages in groups get the combined status of all recipients.
        List<Message> ownMessages = messages.stream()
                .filter(m -> m.getSenderId().equals(viewerId) && m.getType() != MessageType.SYSTEM)
                .toList();
        Set<UUID> groups = groupStatusService.groupIds(ownMessages.stream().map(Message::getConversationId).toList());
        Map<UUID, String> groupStatuses = groupStatusService.combinedStatuses(
                ownMessages.stream().filter(m -> groups.contains(m.getConversationId())).toList());

        List<MessageDto> result = new ArrayList<>();
        for (Message message : messages) {
            List<MessageReceipt> receipts = receiptsByMessage.getOrDefault(message.getId(), List.of());
            boolean system = message.getType() == MessageType.SYSTEM;
            boolean mine = message.getSenderId().equals(viewerId);
            String status = null;
            String myReceipt = null;
            if (!system && mine) {
                status = groupStatuses.containsKey(message.getId())
                        ? groupStatuses.get(message.getId())
                        : privateStatus(receipts, visibility);
            } else if (!system) {
                myReceipt = myReceipt(receipts, viewerId);
            }
            MessageAttachment attachment = attachments.get(message.getId());
            result.add(new MessageDto(
                    message.getId(), message.getConversationId(), message.getSenderId(),
                    message.getType().name(), message.getContent(), message.getCreatedAt(), status, myReceipt,
                    attachment == null ? null : AttachmentDto.from(attachment)));
        }
        return result;
    }

    public MessageDto toDto(Message message, UUID viewerId) {
        return toDtos(List.of(message), viewerId).get(0);
    }

    /** The version sent live to every device: nobody has acknowledged it yet. SYSTEM messages have no status. */
    public MessageDto toLiveDto(Message message) {
        return toLiveDto(message, null);
    }

    /** Same, for a message with an attachment (the attachment is the one that was just saved with it). */
    public MessageDto toLiveDto(Message message, MessageAttachment attachment) {
        String status = message.getType() == MessageType.SYSTEM ? null : "SENT";
        return new MessageDto(message.getId(), message.getConversationId(), message.getSenderId(),
                message.getType().name(), message.getContent(), message.getCreatedAt(), status, null,
                attachment == null ? null : AttachmentDto.from(attachment));
    }

    /** Private chat: SENT -> DELIVERED -> READ. READ is only shown when both users allow read receipts. */
    private String privateStatus(List<MessageReceipt> receipts, ReadVisibility visibility) {
        String status = "SENT";
        for (MessageReceipt receipt : receipts) {
            if (receipt.getStatus() == ReceiptStatus.READ && visibility.isVisibleFor(receipt.getRecipientId())) {
                return "READ";
            }
            status = "DELIVERED";
        }
        return status;
    }

    private String myReceipt(List<MessageReceipt> receipts, UUID viewerId) {
        String result = null;
        for (MessageReceipt receipt : receipts) {
            if (!receipt.getRecipientId().equals(viewerId)) {
                continue;
            }
            if (receipt.getStatus() == ReceiptStatus.READ) {
                return "READ";
            }
            result = "DELIVERED";
        }
        return result;
    }

    /**
     * "Read" is visible to the sender only if the sender AND the recipient have read receipts turned on
     * (the same rule as in WhatsApp, private chats only). The settings are asked lazily: no READ receipt, no call.
     */
    private class ReadVisibility implements Predicate<UUID> {
        private final UUID viewerId;
        private Boolean viewerAllows;
        private final Map<UUID, Boolean> recipientAllows = new HashMap<>();

        ReadVisibility(UUID viewerId) {
            this.viewerId = viewerId;
        }

        boolean isVisibleFor(UUID recipientId) {
            return test(recipientId);
        }

        @Override
        public boolean test(UUID recipientId) {
            if (viewerAllows == null) {
                viewerAllows = userClient.readReceiptsEnabled(viewerId);
            }
            if (!viewerAllows) {
                return false;
            }
            return recipientAllows.computeIfAbsent(recipientId, userClient::readReceiptsEnabled);
        }
    }
}
