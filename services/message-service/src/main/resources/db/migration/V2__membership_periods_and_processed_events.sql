-- Phase 4 (groups).
--
-- 1. conversation_members becomes a copy of conversation-service's MEMBERSHIP PERIODS: one row per
--    (conversation, user, joined_at). A member who is removed and added again has two rows. A message is
--    visible to a user only if it was created inside one of that user's periods (joined_at <= created_at <= left_at).
--    The old rows (Phase 3) all belong to private chats; nobody ever leaves a private chat, so they stay valid.
ALTER TABLE conversation_members
    DROP INDEX uk_members_conversation_user,
    ADD UNIQUE KEY uk_members_period (conversation_id, user_id, joined_at);

-- 2. PRIVATE or GROUP, copied from conversation-service, so we know which chats are groups without asking it
--    (groups show read receipts differently). Every Phase 3 row is a private chat.
ALTER TABLE conversation_members
    ADD COLUMN conversation_type VARCHAR(20) NOT NULL DEFAULT 'PRIVATE' AFTER user_id;

-- 3. Kafka events (conversation.events) that were already handled: a repeated event must do nothing.
CREATE TABLE processed_events (
    event_id     CHAR(36)    NOT NULL,
    processed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (event_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
