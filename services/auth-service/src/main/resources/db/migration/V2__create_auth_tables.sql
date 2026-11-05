-- Auth service tables (auth_db). UUIDs are stored as CHAR(36), times are UTC DATETIME(6).

CREATE TABLE auth_users (
    id                  CHAR(36)     NOT NULL,
    username            VARCHAR(30)  NOT NULL,
    display_name        VARCHAR(50)  NOT NULL,
    email              VARCHAR(254) NOT NULL,
    phone               VARCHAR(16)  NULL,
    password_hash       VARCHAR(100) NOT NULL,
    role                VARCHAR(20)  NOT NULL,
    email_verified      BOOLEAN      NOT NULL DEFAULT FALSE,
    phone_verified      BOOLEAN      NOT NULL DEFAULT FALSE,
    status              VARCHAR(20)  NOT NULL,
    failed_login_count  INT          NOT NULL DEFAULT 0,
    locked_until        DATETIME(6)  NULL,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_auth_users_username (username),
    UNIQUE KEY uk_auth_users_email (email),
    UNIQUE KEY uk_auth_users_phone (phone)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- One row per logged-in device ("active devices" screen)
CREATE TABLE login_sessions (
    id             CHAR(36)     NOT NULL,
    user_id        CHAR(36)     NOT NULL,
    device_name    VARCHAR(100) NOT NULL,
    user_agent     VARCHAR(255) NULL,
    ip_address     VARCHAR(64)  NULL,
    created_at     DATETIME(6)  NOT NULL,
    last_active_at DATETIME(6)  NOT NULL,
    revoked        BOOLEAN      NOT NULL DEFAULT FALSE,
    PRIMARY KEY (id),
    KEY idx_login_sessions_user (user_id),
    CONSTRAINT fk_login_sessions_user FOREIGN KEY (user_id) REFERENCES auth_users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Only the SHA-256 hash of a refresh token is stored, never the token itself.
CREATE TABLE refresh_tokens (
    id             CHAR(36)    NOT NULL,
    user_id        CHAR(36)    NOT NULL,
    session_id     CHAR(36)    NOT NULL,
    token_hash     CHAR(64)    NOT NULL,
    expires_at     DATETIME(6) NOT NULL,
    revoked        BOOLEAN     NOT NULL DEFAULT FALSE,
    replaced_by_id CHAR(36)    NULL,
    created_at     DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refresh_tokens_hash (token_hash),
    KEY idx_refresh_tokens_session (session_id),
    KEY idx_refresh_tokens_user (user_id),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES auth_users (id),
    CONSTRAINT fk_refresh_tokens_session FOREIGN KEY (session_id) REFERENCES login_sessions (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- One-time codes (6 digits). Only a BCrypt hash of the code is stored.
CREATE TABLE otp_verifications (
    id         CHAR(36)     NOT NULL,
    user_id    CHAR(36)     NOT NULL,
    purpose    VARCHAR(30)  NOT NULL,
    otp_hash   VARCHAR(100) NOT NULL,
    expires_at DATETIME(6)  NOT NULL,
    attempts   INT          NOT NULL DEFAULT 0,
    used       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    KEY idx_otp_user_purpose (user_id, purpose, created_at),
    CONSTRAINT fk_otp_user FOREIGN KEY (user_id) REFERENCES auth_users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE password_reset_tokens (
    id         CHAR(36)    NOT NULL,
    user_id    CHAR(36)    NOT NULL,
    token_hash CHAR(64)    NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    used       BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_reset_tokens_hash (token_hash),
    KEY idx_reset_tokens_user (user_id, created_at),
    CONSTRAINT fk_reset_tokens_user FOREIGN KEY (user_id) REFERENCES auth_users (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Who did what, for sensitive account operations. Never store secrets here.
CREATE TABLE audit_logs (
    id         CHAR(36)    NOT NULL,
    user_id    CHAR(36)    NULL,
    action     VARCHAR(40) NOT NULL,
    ip_address VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_audit_user (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
