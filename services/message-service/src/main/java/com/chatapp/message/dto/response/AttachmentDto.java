package com.chatapp.message.dto.response;

import java.util.UUID;

import com.chatapp.message.entity.MessageAttachment;

/**
 * What a client needs to show an attachment. The file itself is fetched with a signed link
 * (GET /api/messages/{id}/attachment/url), never through this service.
 */
public record AttachmentDto(
        UUID mediaId,
        String kind,
        String fileName,
        String mimeType,
        long sizeBytes,
        Integer width,
        Integer height,
        Integer durationSeconds,
        boolean hasThumbnail) {

    public static AttachmentDto from(MessageAttachment attachment) {
        return new AttachmentDto(attachment.getMediaId(), attachment.getKind(), attachment.getFileName(),
                attachment.getMimeType(), attachment.getSizeBytes(), attachment.getWidth(), attachment.getHeight(),
                attachment.getDurationSeconds(), attachment.isHasThumbnail());
    }
}
