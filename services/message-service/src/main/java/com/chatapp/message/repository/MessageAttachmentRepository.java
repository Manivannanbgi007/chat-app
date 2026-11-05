package com.chatapp.message.repository;

import java.util.UUID;

import com.chatapp.message.entity.MessageAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

/** The primary key is the message id, so findAllById(messageIds) loads the attachments of a page of messages. */
public interface MessageAttachmentRepository extends JpaRepository<MessageAttachment, UUID> {
}
