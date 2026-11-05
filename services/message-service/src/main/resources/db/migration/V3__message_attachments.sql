-- Phase 5 (media): a message can carry ONE attachment (image, video, audio, voice note or document).
-- The file itself lives in the object storage and is managed by media-service. Here we keep a SNAPSHOT of its
-- metadata, so history pages need no call to media-service.
CREATE TABLE message_attachments (
    message_id       CHAR(36)     NOT NULL,
    media_id         CHAR(36)     NOT NULL,
    kind             VARCHAR(20)  NOT NULL,
    file_name        VARCHAR(150) NOT NULL,
    mime_type        VARCHAR(100) NOT NULL,
    size_bytes       BIGINT       NOT NULL,
    width            INT          NULL,
    height           INT          NULL,
    duration_seconds INT          NULL,
    has_thumbnail    BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (message_id),
    KEY idx_attachments_media (media_id),
    CONSTRAINT fk_attachments_message FOREIGN KEY (message_id) REFERENCES messages (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
