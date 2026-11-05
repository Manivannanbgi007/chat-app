package com.chatapp.message.service;

import java.util.UUID;

import com.chatapp.message.client.MediaClient;
import com.chatapp.message.dto.response.DownloadLinkDto;
import com.chatapp.message.entity.Message;
import com.chatapp.message.entity.MessageAttachment;
import com.chatapp.message.exception.BadRequestException;
import com.chatapp.message.exception.ResourceNotFoundException;
import com.chatapp.message.repository.MessageAttachmentRepository;
import com.chatapp.message.repository.MessageRepository;
import org.springframework.stereotype.Service;

/**
 * Gives a user a signed download link for the attachment of a message.
 *
 * THE RULE BELONGS TO THE MESSAGE: whoever may see the message may get a link to its file, nobody else can
 * (same removed-member rule as for reading the message). media-service only signs the link; it does not know
 * about conversations.
 */
@Service
public class AttachmentService {

    private final MessageRepository messageRepository;
    private final MessageAttachmentRepository attachmentRepository;
    private final ConversationAccessService accessService;
    private final MediaClient mediaClient;

    public AttachmentService(MessageRepository messageRepository, MessageAttachmentRepository attachmentRepository,
                             ConversationAccessService accessService, MediaClient mediaClient) {
        this.messageRepository = messageRepository;
        this.attachmentRepository = attachmentRepository;
        this.accessService = accessService;
        this.mediaClient = mediaClient;
    }

    /** variant: "original" (default) or "thumbnail". Every failure to see the file is the same 404. */
    public DownloadLinkDto link(UUID userId, UUID messageId, String variant) {
        String chosen = variant == null ? "original" : variant;
        if (!chosen.equals("original") && !chosen.equals("thumbnail")) {
            throw new BadRequestException("variant must be 'original' or 'thumbnail'", "VALIDATION");
        }

        Message message = messageRepository.findById(messageId).orElseThrow(this::notFound);
        // current or former member of the conversation (also refreshes our copy of the membership periods) ...
        accessService.requireReader(message.getConversationId(), userId);
        // ... and the message must have been created inside one of the user's membership periods
        messageRepository.findVisible(messageId, userId).orElseThrow(this::notFound);

        MessageAttachment attachment = attachmentRepository.findById(messageId).orElseThrow(this::notFound);
        if (chosen.equals("thumbnail") && !attachment.isHasThumbnail()) {
            throw notFound();
        }
        MediaClient.DownloadLink link = mediaClient.downloadLink(attachment.getMediaId(), userId, chosen);
        return new DownloadLinkDto(link.url(), link.expiresAt());
    }

    private ResourceNotFoundException notFound() {
        return new ResourceNotFoundException("Attachment not found");
    }
}
