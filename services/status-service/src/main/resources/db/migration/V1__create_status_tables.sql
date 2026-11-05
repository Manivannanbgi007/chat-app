-- status_db (status-service, Phase 8). UUIDs are CHAR(36), times are UTC DATETIME(6).

-- One status (story). It is visible until expires_at (created_at + 24 h); a job then deletes it.
-- TEXT statuses have text + background_color; IMAGE / VIDEO statuses have a file in media-service (media_id)
-- and a copy of the file's metadata, so showing a status never needs a call to media-service.
CREATE TABLE statuses (
    id               CHAR(36)     NOT NULL,
    owner_id         CHAR(36)     NOT NULL,      -- a user of user-service (no foreign key across services)
    type             VARCHAR(10)  NOT NULL,      -- TEXT, IMAGE, VIDEO
    text             TEXT         NULL,
    background_color VARCHAR(7)   NULL,
    caption          VARCHAR(700) NULL,
    media_id         CHAR(36)     NULL,
    mime_type        VARCHAR(100) NULL,
    width            INT          NULL,
    height           INT          NULL,
    duration_seconds INT          NULL,
    has_thumbnail    BOOLEAN      NOT NULL DEFAULT FALSE,
    idempotency_key  VARCHAR(64)  NOT NULL,
    created_at       DATETIME(6)  NOT NULL,
    expires_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    -- posting the same status again (a retry) can never create a second one
    UNIQUE KEY uk_statuses_owner_key (owner_id, idempotency_key),
    KEY idx_statuses_owner_expires (owner_id, expires_at),
    KEY idx_statuses_expires (expires_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- "viewer_id saw this status". Deleting a status deletes its views.
CREATE TABLE status_views (
    id        CHAR(36)    NOT NULL,
    status_id CHAR(36)    NOT NULL,
    viewer_id CHAR(36)    NOT NULL,
    viewed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_status_views_status_viewer (status_id, viewer_id),
    KEY idx_status_views_viewer (viewer_id),
    CONSTRAINT fk_status_views_status FOREIGN KEY (status_id) REFERENCES statuses (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Who may see my statuses. No row = the default (CONTACTS).
CREATE TABLE status_privacy (
    owner_id   CHAR(36)    NOT NULL,
    mode       VARCHAR(20) NOT NULL,             -- CONTACTS, CONTACTS_EXCEPT, ONLY_SHARE_WITH
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (owner_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- The people of the current mode: the excepted ones (CONTACTS_EXCEPT) or the chosen ones (ONLY_SHARE_WITH).
CREATE TABLE status_privacy_members (
    owner_id CHAR(36) NOT NULL,
    user_id  CHAR(36) NOT NULL,
    PRIMARY KEY (owner_id, user_id),
    KEY idx_status_privacy_members_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Files of deleted or expired statuses that media-service still has to delete ("release").
-- The status row is deleted at once (in the same transaction that adds a row here), and the file is released by
-- a job that retries until media-service confirms. So a file is never forgotten when media-service is down.
CREATE TABLE media_releases (
    id              CHAR(36)     NOT NULL,
    media_id        CHAR(36)     NOT NULL,
    owner_id        CHAR(36)     NOT NULL,
    reference       VARCHAR(120) NOT NULL,       -- the reference the file was attached with: status:<ownerId>:<key>
    attempts        INT          NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6)  NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_media_releases_due (next_attempt_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
