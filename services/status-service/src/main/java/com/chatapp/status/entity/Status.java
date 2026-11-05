package com.chatapp.status.entity;

import java.time.Instant;
import java.util.UUID;

import com.chatapp.status.enums.StatusType;
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

/**
 * One status (story). Visible until expiresAt; afterwards every query ignores it and a job deletes it.
 * For IMAGE / VIDEO the file lives in media-service; we keep a copy of its metadata (mimeType, size in pixels,
 * duration, thumbnail yes/no) so showing a status needs no call to media-service.
 */
@Entity
@Table(name = "statuses")
@Getter
@Setter
@NoArgsConstructor
public class Status {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private StatusType type;

    @Column(columnDefinition = "TEXT")
    private String text;

    @Column(name = "background_color", length = 7)
    private String backgroundColor;

    @Column(length = 700)
    private String caption;

    @Column(name = "media_id")
    private UUID mediaId;

    @Column(name = "mime_type", length = 100)
    private String mimeType;

    private Integer width;

    private Integer height;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "has_thumbnail", nullable = false)
    private boolean hasThumbnail;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    public boolean hasMedia() {
        return mediaId != null;
    }

    /** The reference the file was attached with in media-service. Only this reference can release (delete) it. */
    public String mediaReference() {
        return referenceFor(ownerId, idempotencyKey);
    }

    public static String referenceFor(UUID ownerId, String idempotencyKey) {
        return "status:" + ownerId + ":" + idempotencyKey;
    }
}
