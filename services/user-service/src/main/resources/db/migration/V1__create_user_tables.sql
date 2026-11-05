-- user_db: everything the user-service owns. Ids are UUIDs stored as CHAR(36).
-- Foreign keys exist only inside this database (never to another service's data).

-- The id is the same id auth-service created for this user (copied from UserRegisteredEvent).
CREATE TABLE users (
    id             CHAR(36)     NOT NULL,
    username       VARCHAR(30)  NOT NULL,
    email          VARCHAR(254) NOT NULL,
    phone          VARCHAR(16)  NULL,
    account_status VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_users_username (username),
    UNIQUE KEY uk_users_email (email),
    KEY idx_users_phone (phone)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE user_profiles (
    user_id           CHAR(36)     NOT NULL,
    display_name      VARCHAR(50)  NOT NULL,
    profile_image_url VARCHAR(500) NULL,
    about             VARCHAR(139) NULL,
    updated_at        DATETIME(6)  NOT NULL,
    PRIMARY KEY (user_id),
    KEY idx_user_profiles_display_name (display_name),
    CONSTRAINT fk_user_profiles_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE user_privacy_settings (
    user_id       CHAR(36)    NOT NULL,
    last_seen     VARCHAR(10) NOT NULL DEFAULT 'EVERYONE',
    profile_photo VARCHAR(10) NOT NULL DEFAULT 'EVERYONE',
    about         VARCHAR(10) NOT NULL DEFAULT 'EVERYONE',
    group_add     VARCHAR(10) NOT NULL DEFAULT 'EVERYONE',
    read_receipts BOOLEAN     NOT NULL DEFAULT TRUE,
    updated_at    DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id),
    CONSTRAINT fk_privacy_user FOREIGN KEY (user_id) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE contacts (
    id              CHAR(36)    NOT NULL,
    owner_id        CHAR(36)    NOT NULL,
    contact_user_id CHAR(36)    NOT NULL,
    nickname        VARCHAR(50) NULL,
    created_at      DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_contacts_owner_contact (owner_id, contact_user_id),
    KEY idx_contacts_contact_user (contact_user_id),
    CONSTRAINT fk_contacts_owner FOREIGN KEY (owner_id) REFERENCES users (id),
    CONSTRAINT fk_contacts_contact FOREIGN KEY (contact_user_id) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE blocked_users (
    id         CHAR(36)    NOT NULL,
    blocker_id CHAR(36)    NOT NULL,
    blocked_id CHAR(36)    NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_blocked_blocker_blocked (blocker_id, blocked_id),
    KEY idx_blocked_blocked (blocked_id),
    CONSTRAINT fk_blocked_blocker FOREIGN KEY (blocker_id) REFERENCES users (id),
    CONSTRAINT fk_blocked_blocked FOREIGN KEY (blocked_id) REFERENCES users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Kafka consumer idempotency: an event id is stored when it has been handled.
CREATE TABLE processed_events (
    event_id     CHAR(36)    NOT NULL,
    processed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (event_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
