-- conversation_db. UUIDs are CHAR(36), times are UTC DATETIME(6).
-- Phase 3 holds one-to-one conversations only; group tables are added in Phase 4.

CREATE TABLE conversations (
    id              CHAR(36)    NOT NULL,
    type            VARCHAR(20) NOT NULL,
    created_by      CHAR(36)    NOT NULL,
    created_at      DATETIME(6) NOT NULL,
    last_message_at DATETIME(6) NULL,          -- filled from MessageCreatedEvent (a time only, never message text)
    PRIMARY KEY (id),
    KEY idx_conversations_last_message (last_message_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE conversation_participants (
    id              CHAR(36)    NOT NULL,
    conversation_id CHAR(36)    NOT NULL,
    user_id         CHAR(36)    NOT NULL,      -- id of a user owned by user-service (no foreign key across services)
    role            VARCHAR(20) NOT NULL,
    joined_at       DATETIME(6) NOT NULL,
    left_at         DATETIME(6) NULL,
    active          BOOLEAN     NOT NULL DEFAULT TRUE,
    PRIMARY KEY (id),
    UNIQUE KEY uk_participant_conversation_user (conversation_id, user_id),
    KEY idx_participants_user_active (user_id, active),
    CONSTRAINT fk_participants_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- A pair of users can have only ONE private conversation. The smaller id is always stored in
-- user_low_id, so (A,B) and (B,A) are the same row and the unique key stops duplicates,
-- even when two requests arrive at the same moment.
CREATE TABLE private_conversation_keys (
    conversation_id CHAR(36) NOT NULL,
    user_low_id     CHAR(36) NOT NULL,
    user_high_id    CHAR(36) NOT NULL,
    PRIMARY KEY (conversation_id),
    UNIQUE KEY uk_private_pair (user_low_id, user_high_id),
    CONSTRAINT fk_private_key_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Transactional outbox: the event is saved together with the business data and sent to Kafka later.
CREATE TABLE outbox_events (
    id           CHAR(36)     NOT NULL,
    aggregate_id CHAR(36)     NOT NULL,
    event_type   VARCHAR(100) NOT NULL,
    payload      JSON         NOT NULL,
    created_at   DATETIME(6)  NOT NULL,
    published    BOOLEAN      NOT NULL DEFAULT FALSE,
    published_at DATETIME(6)  NULL,
    PRIMARY KEY (id),
    INDEX idx_outbox_unpublished (published, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Kafka events that were already handled (a repeated event must do nothing).
CREATE TABLE processed_events (
    event_id     CHAR(36)    NOT NULL,
    processed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (event_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
