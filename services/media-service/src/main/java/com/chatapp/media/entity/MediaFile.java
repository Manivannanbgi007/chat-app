package com.chatapp.media.entity;

import java.time.Instant;
import java.util.UUID;

import com.chatapp.media.enums.MediaKind;
import com.chatapp.media.enums.MediaStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

/** One uploaded file. The bytes are in the object storage; this row says where and who owns it. */
@Entity
@Table(name = "media_files")
@Getter
@Setter
@NoArgsConstructor
public class MediaFile {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MediaKind kind;

    @Column(name = "original_name", nullable = false, length = 150)
    private String originalName;

    /** The type found by looking at the bytes of the file, not the one the browser claimed. */
    @Column(name = "mime_type", nullable = false, length = 100)
    private String mimeType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "object_key", nullable = false, length = 200)
    private String objectKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MediaStatus status;

    /** "senderId:idempotencyKey" of the message that uses this file (makes attaching idempotent). */
    @Column(name = "attach_reference", length = 120)
    private String attachReference;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "attached_at")
    private Instant attachedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
