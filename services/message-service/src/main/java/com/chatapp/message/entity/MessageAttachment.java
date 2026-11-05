package com.chatapp.message.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The attachment of a message: a copy of the file's metadata (the file itself is in the object storage,
 * managed by media-service). The message id is also the primary key: one attachment per message.
 */
@Entity
@Table(name = "message_attachments")
@Getter
@Setter
@NoArgsConstructor
public class MessageAttachment {

    @Id
    @Column(name = "message_id")
    private UUID messageId;

    @Column(name = "media_id", nullable = false)
    private UUID mediaId;

    @Column(nullable = false, length = 20)
    private String kind;

    @Column(name = "file_name", nullable = false, length = 150)
    private String fileName;

    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    private Integer width;

    private Integer height;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "has_thumbnail", nullable = false)
    private boolean hasThumbnail;
}
