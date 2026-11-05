-- Media service tables (media_db). UUIDs are CHAR(36), times are UTC DATETIME(6).
-- The files themselves live in the object storage (S3 API); MySQL only knows where they are.

CREATE TABLE media_files (
    id               CHAR(36)     NOT NULL,
    owner_id         CHAR(36)     NOT NULL,                 -- the user who uploaded the file
    kind             VARCHAR(20)  NOT NULL,                 -- IMAGE, VIDEO, AUDIO, VOICE, DOCUMENT
    original_name    VARCHAR(150) NOT NULL,                 -- cleaned file name (shown to people, never used as a path)
    mime_type        VARCHAR(100) NOT NULL,                 -- the DETECTED type, not what the browser claimed
    size_bytes       BIGINT       NOT NULL,
    object_key       VARCHAR(200) NOT NULL,                 -- yyyy/MM/<uuid>, random
    status           VARCHAR(20)  NOT NULL,                 -- UPLOADED -> ATTACHED, or DELETED
    attach_reference VARCHAR(120) NULL,                     -- "<senderId>:<idempotencyKey>" of the message that uses it
    created_at       DATETIME(6)  NOT NULL,
    attached_at      DATETIME(6)  NULL,
    deleted_at       DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_media_object_key (object_key),
    KEY idx_media_status_created (status, created_at),     -- the cleanup job
    KEY idx_media_owner (owner_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE media_metadata (
    media_id             CHAR(36)     NOT NULL,
    width                INT          NULL,
    height               INT          NULL,
    duration_seconds     INT          NULL,                 -- measured by the client, not checked by us
    thumbnail_object_key VARCHAR(200) NULL,
    thumbnail_size_bytes BIGINT       NULL,
    PRIMARY KEY (media_id),
    CONSTRAINT fk_metadata_media FOREIGN KEY (media_id) REFERENCES media_files (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE media_access_logs (
    id         CHAR(36)    NOT NULL,
    media_id   CHAR(36)    NOT NULL,
    user_id    CHAR(36)    NOT NULL,
    action     VARCHAR(30) NOT NULL,                        -- UPLOAD, VIEW_LINK, DOWNLOAD_LINK, ATTACH, DELETE, EXPIRE
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_access_media (media_id, created_at),
    CONSTRAINT fk_access_media FOREIGN KEY (media_id) REFERENCES media_files (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
