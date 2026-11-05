-- call_db (call-signaling-service). UUIDs are CHAR(36), times are UTC DATETIME(6).
-- One-to-one calls only: the caller and the callee are columns of the call (no participants table).

CREATE TABLE calls (
    id                  CHAR(36)    NOT NULL,
    caller_id           CHAR(36)    NOT NULL,   -- user ids belong to user-service (no foreign key across services)
    callee_id           CHAR(36)    NOT NULL,
    type                VARCHAR(10) NOT NULL,   -- AUDIO / VIDEO
    status              VARCHAR(20) NOT NULL,   -- RINGING / ACCEPTED / ENDED / MISSED / REJECTED / BUSY
    end_reason          VARCHAR(30) NULL,       -- HANGUP / CONNECTION_LOST / NO_ANSWER / CANCELLED / DECLINED / BUSY
    caller_device_id    CHAR(36)    NOT NULL,   -- the login session (device) that started the call
    callee_device_id    CHAR(36)    NULL,       -- the device that accepted (null until then)
    created_at          DATETIME(6) NOT NULL,
    answered_at         DATETIME(6) NULL,
    ended_at            DATETIME(6) NULL,
    last_caller_ping_at DATETIME(6) NULL,       -- "still there" signals during an accepted call
    last_callee_ping_at DATETIME(6) NULL,
    updated_at          DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    KEY idx_calls_caller (caller_id, created_at),   -- call history
    KEY idx_calls_callee (callee_id, created_at),
    KEY idx_calls_status (status, created_at)       -- the time-out job
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- One row per user who is in a call RIGHT NOW. The primary key makes "is this user busy?" atomic:
-- two calls that want the same person at the same moment cannot both insert the row.
-- Rows are deleted when the call reaches a final state.
CREATE TABLE call_slots (
    user_id    CHAR(36)    NOT NULL,
    call_id    CHAR(36)    NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (user_id),
    KEY idx_call_slots_call (call_id),
    CONSTRAINT fk_call_slots_call FOREIGN KEY (call_id) REFERENCES calls (id)
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
