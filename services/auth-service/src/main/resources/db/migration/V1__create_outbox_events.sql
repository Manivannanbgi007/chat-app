-- Transactional outbox: business data and the event to publish are saved in ONE database
-- transaction; a scheduled publisher later sends unpublished rows to Kafka.
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
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;
