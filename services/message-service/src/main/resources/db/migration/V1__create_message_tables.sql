-- Message service tables (message_db). UUIDs are CHAR(36), times are UTC DATETIME(6).

-- Message ids are UUIDv7 (they sort by time). Order in a conversation is (created_at, id).
CREATE TABLE messages (
    id              CHAR(36)     NOT NULL,
    conversation_id CHAR(36)     NOT NULL,
    sender_id       CHAR(36)     NOT NULL,
    type            VARCHAR(20)  NOT NULL,
    content         TEXT         NOT NULL,
    idempotency_key VARCHAR(64)  NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    -- the same user can never save two messages with the same key (retries and reconnects are safe)
    UNIQUE KEY uk_messages_sender_key (sender_id, idempotency_key),
    -- history pages: newest first inside one conversation
    KEY idx_messages_conversation_time (conversation_id, created_at, id),
    -- sync across conversations
    KEY idx_messages_created (created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- One row per (message, recipient, device). No row = only SENT. DELIVERED can become READ, never backwards.
CREATE TABLE message_receipts (
    id           CHAR(36)    NOT NULL,
    message_id   CHAR(36)    NOT NULL,
    recipient_id CHAR(36)    NOT NULL,
    device_id    CHAR(36)    NOT NULL,
    status       VARCHAR(20) NOT NULL,
    delivered_at DATETIME(6) NOT NULL,
    read_at      DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_receipts_message_recipient_device (message_id, recipient_id, device_id),
    KEY idx_receipts_recipient_status (recipient_id, status),
    CONSTRAINT fk_receipts_message FOREIGN KEY (message_id) REFERENCES messages (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Our own copy of "who is in which conversation", filled from conversation-service's answers.
-- Only used to find a user's conversations (unread counts, sync). Access checks always ask conversation-service.
CREATE TABLE conversation_members (
    id              CHAR(36)    NOT NULL,
    conversation_id CHAR(36)    NOT NULL,
    user_id         CHAR(36)    NOT NULL,
    joined_at       DATETIME(6) NOT NULL,
    left_at         DATETIME(6) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_members_conversation_user (conversation_id, user_id),
    KEY idx_members_user (user_id, left_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Transactional outbox: events are saved with the business data and sent to Kafka afterwards.
CREATE TABLE outbox_events (
    id           CHAR(36)     NOT NULL,
    aggregate_id CHAR(36)     NOT NULL,
    event_type   VARCHAR(100) NOT NULL,
    payload      JSON         NOT NULL,
    created_at   DATETIME(6)  NOT NULL,
    published    BOOLEAN      NOT NULL DEFAULT FALSE,
    published_at DATETIME(6)  NULL,
    PRIMARY KEY (id),
    KEY idx_outbox_unpublished (published, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
