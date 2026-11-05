package com.chatapp.media.entity;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Extra facts about a file: image size, duration, and where the thumbnail is stored. */
@Entity
@Table(name = "media_metadata")
@Getter
@Setter
@NoArgsConstructor
public class MediaMetadata {

    /** Same id as the media file (one row per file). */
    @Id
    @Column(name = "media_id")
    private UUID mediaId;

    private Integer width;

    private Integer height;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "thumbnail_object_key", length = 200)
    private String thumbnailObjectKey;

    @Column(name = "thumbnail_size_bytes")
    private Long thumbnailSizeBytes;

    public boolean hasThumbnail() {
        return thumbnailObjectKey != null;
    }
}
