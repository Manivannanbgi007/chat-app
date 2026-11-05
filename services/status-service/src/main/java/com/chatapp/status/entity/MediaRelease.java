package com.chatapp.status.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * "media-service still has to delete this file". Written in the same transaction that deletes a status, and removed
 * once media-service confirmed the release, so a file is never forgotten while media-service is down.
 */
@Entity
@Table(name = "media_releases")
@Getter
@Setter
@NoArgsConstructor
public class MediaRelease {

    @Id
    private UUID id;

    @Column(name = "media_id", nullable = false)
    private UUID mediaId;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(nullable = false, length = 120)
    private String reference;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    public static MediaRelease of(Status status, Instant now) {
        MediaRelease release = new MediaRelease();
        release.setId(UUID.randomUUID());
        release.setMediaId(status.getMediaId());
        release.setOwnerId(status.getOwnerId());
        release.setReference(status.mediaReference());
        release.setAttempts(0);
        release.setNextAttemptAt(now);
        release.setCreatedAt(now);
        return release;
    }
}
