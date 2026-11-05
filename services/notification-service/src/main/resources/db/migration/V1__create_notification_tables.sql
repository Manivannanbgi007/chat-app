-- Notification service tables (notification_db). UUIDs are CHAR(36), times are UTC DATETIME(6).

-- One row per notification. Message notifications are AGGREGATED per chat while unread: "3 new messages".
-- unread_key makes that safe: while a notification is unread it holds "<user>:<type>:<chat or event>",
-- and the UNIQUE index allows only one unread row with that key. When the notification is read the key
-- becomes NULL (MySQL allows many NULLs in a unique index), so the next message starts a new notification.
-- It is written with INSERT ... ON DUPLICATE KEY UPDATE item_count = item_count + 1.
CREATE TABLE notifications (
    id              CHAR(36)     NOT NULL,
    user_id         CHAR(36)     NOT NULL,
    type            VARCHAR(30)  NOT NULL,   -- NEW_MESSAGES, GROUP_ADDED, GROUP_REMOVED, GROUP_ADMIN
    title           VARCHAR(200) NOT NULL,
    body            VARCHAR(500) NOT NULL,   -- never message text
    conversation_id CHAR(36)     NULL,
    item_count      INT          NOT NULL DEFAULT 1,
    is_read         BOOLEAN      NOT NULL DEFAULT FALSE,
    unread_key      VARCHAR(200) NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_notifications_unread_key (unread_key),
    KEY idx_notifications_user_updated (user_id, updated_at),
    KEY idx_notifications_user_read (user_id, is_read)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- No row = all defaults (everything on).
CREATE TABLE notification_preferences (
    user_id          CHAR(36)    NOT NULL,
    push_enabled     BOOLEAN     NOT NULL DEFAULT TRUE,
    messages_enabled BOOLEAN     NOT NULL DEFAULT TRUE,
    groups_enabled   BOOLEAN     NOT NULL DEFAULT TRUE,
    calls_enabled    BOOLEAN     NOT NULL DEFAULT TRUE,
    updated_at       DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Push tokens of the user's browsers / phones. A token belongs to ONE user (the last one who registered it).
CREATE TABLE device_tokens (
    id           CHAR(36)     NOT NULL,
    user_id      CHAR(36)     NOT NULL,
    token        TEXT         NOT NULL,   -- up to 4096 characters
    token_hash   CHAR(64)     NOT NULL,   -- SHA-256 of the token: the unique key (tokens are long)
    platform     VARCHAR(10)  NOT NULL,   -- WEB, ANDROID, IOS
    created_at   DATETIME(6)  NOT NULL,
    last_seen_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_device_tokens_hash (token_hash),
    KEY idx_device_tokens_user (user_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- One row per push to send. Created in the same transaction as the notification (so none is lost),
-- sent by a scheduled job with retries: PENDING -> SENT, or FAILED (retry later) -> DEAD after the last try.
CREATE TABLE notification_delivery_logs (
    id              CHAR(36)     NOT NULL,
    notification_id CHAR(36)     NOT NULL,
    user_id         CHAR(36)     NOT NULL,
    channel         VARCHAR(10)  NOT NULL,   -- PUSH
    status          VARCHAR(10)  NOT NULL,   -- PENDING, SENT, FAILED, DEAD
    attempts        INT          NOT NULL DEFAULT 0,
    last_error      VARCHAR(300) NULL,       -- the exception class only, never content
    next_attempt_at DATETIME(6)  NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_delivery_due (status, next_attempt_at),
    KEY idx_delivery_notification (notification_id),
    CONSTRAINT fk_delivery_notification FOREIGN KEY (notification_id) REFERENCES notifications (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Kafka events that were already handled: a repeated event must do nothing.
CREATE TABLE processed_events (
    event_id     CHAR(36)    NOT NULL,
    processed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (event_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
