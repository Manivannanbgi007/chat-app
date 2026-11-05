-- Phase 4: groups, membership history and per-user chat settings (mute / archive / pin).

-- Name, description and rules of a GROUP conversation (one row per group).
CREATE TABLE group_settings (
    conversation_id      CHAR(36)     NOT NULL,
    name                 VARCHAR(100) NOT NULL,
    description          VARCHAR(500) NULL,
    image_url            VARCHAR(500) NULL,       -- filled when media-service exists (Phase 5)
    only_admins_can_send BOOLEAN      NOT NULL DEFAULT FALSE,
    only_admins_can_edit BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_at           DATETIME(6)  NOT NULL,
    PRIMARY KEY (conversation_id),
    CONSTRAINT fk_group_settings_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Per-user settings of a chat. They live on the participant row: there is exactly one row per user and chat
-- already, so separate mute / archive / pin tables would only add joins.
ALTER TABLE conversation_participants
    ADD COLUMN muted_until DATETIME(6) NULL,      -- muted while this is in the future ("always" = 9999-12-31)
    ADD COLUMN archived_at DATETIME(6) NULL,      -- NULL = not archived
    ADD COLUMN pinned_at   DATETIME(6) NULL;      -- NULL = not pinned

-- Every period in which a user was a member of a conversation.
-- conversation_participants holds the CURRENT state (one row per user and chat, re-used when someone is added
-- again); this table keeps the history, which decides which messages a member may see
-- (a member sees messages created between joined_at and left_at, both inclusive).
CREATE TABLE membership_periods (
    id              CHAR(36)    NOT NULL,
    conversation_id CHAR(36)    NOT NULL,
    user_id         CHAR(36)    NOT NULL,
    joined_at       DATETIME(6) NOT NULL,
    left_at         DATETIME(6) NULL,             -- NULL = still a member
    PRIMARY KEY (id),
    KEY idx_periods_conversation_user (conversation_id, user_id),
    CONSTRAINT fk_periods_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Conversations created before Phase 4 (all private): one period per participant.
INSERT INTO membership_periods (id, conversation_id, user_id, joined_at, left_at)
SELECT UUID(), conversation_id, user_id, joined_at, left_at
FROM conversation_participants;
