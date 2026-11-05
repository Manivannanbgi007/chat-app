package com.chatapp.message.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.chatapp.message.entity.MessageReceipt;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageReceiptRepository extends JpaRepository<MessageReceipt, UUID> {

    List<MessageReceipt> findByMessageIdIn(Collection<UUID> messageIds);

    List<MessageReceipt> findByMessageIdInAndRecipientId(Collection<UUID> messageIds, UUID recipientId);

    Optional<MessageReceipt> findByMessageIdAndRecipientIdAndDeviceId(UUID messageId, UUID recipientId, UUID deviceId);
}
