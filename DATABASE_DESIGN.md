# DATABASE DESIGN

Rules (all services): database per service, UUID primary keys (`CHAR(36)` or `BINARY(16)`; we use `CHAR(36)` for readability), UTC timestamps `DATETIME(6)`, Flyway migrations in `src/main/resources/db/migration` (`V1__init.sql`, ...), foreign keys **only inside** a service database, never across services. Other services' ids are stored as plain columns without FK.

Common columns: `created_at`, `updated_at` (UTC). Soft delete uses a boolean + timestamp.

Presence uses Redis only (see bottom).

---

## auth_db (auth-service)

| Table | Key columns | Notes |
|---|---|---|
| `auth_users` | id, username (unique), display_name, email (unique), phone (unique, nullable), password_hash, role (USER/ADMIN), email_verified, phone_verified, status (ACTIVE/DISABLED), failed_login_count, locked_until | BCrypt hash only. `username` and `display_name` are stored here too so registration can reject duplicates immediately and the login response can show the name; the user-service owns the editable profile. Locking is `locked_until`, not a status. |
| `refresh_tokens` | id, user_id FK, session_id FK, token_hash (unique), expires_at, revoked, replaced_by_id, created_at | Store **hash** of token; rotation chain via `replaced_by_id`; reuse of a revoked token revokes the session |
| `login_sessions` | id, user_id FK, device_name, user_agent, ip_address, last_active_at, revoked | Powers "Active devices" |
| `otp_verifications` | id, user_id FK, purpose (EMAIL_VERIFY now), otp_hash, expires_at, attempts, used, created_at | One row per code sent. A new code invalidates older ones; "resend limits" are counted from `created_at` (60 s gap, 5 per hour). Never store plain OTP |
| `password_reset_tokens` | id, user_id FK, token_hash, expires_at, used | |
| `audit_logs` | id, user_id, action, ip_address, created_at | Sensitive account operations |
| `outbox_events` | see outbox table below | `UserRegisteredEvent` |

Indexes: `auth_users(email)`, `auth_users(phone)`, `refresh_tokens(token_hash)`, `refresh_tokens(user_id)`, `login_sessions(user_id)`.

## user_db (user-service)

| Table | Key columns | Notes |
|---|---|---|
| `users` | id (= auth user id), username (unique), email, phone, account_status, created_at | Created from `UserRegisteredEvent` |
| `user_profiles` | user_id PK/FK, display_name, profile_image_url, about, updated_at | |
| `contacts` | id, owner_id FK, contact_user_id, nickname, created_at | unique(owner_id, contact_user_id) |
| `blocked_users` | id, blocker_id FK, blocked_id, created_at | unique(blocker_id, blocked_id) |
| `user_privacy_settings` | user_id PK/FK, last_seen (EVERYONE/CONTACTS/NOBODY), profile_photo, about, read_receipts (bool), group_add (EVERYONE/CONTACTS/NOBODY) | defaults created with the user |
| `outbox_events`, `processed_events` | | |

Indexes: `users(username)`, `users(phone)`, FULLTEXT or prefix index on `user_profiles(display_name)` for search.

## conversation_db (conversation-service)

| Table | Key columns | Notes |
|---|---|---|
| `conversations` | id, type (PRIVATE/GROUP), created_by, created_at, last_message_at, last_message_preview | `last_message_*` updated from `MessageCreatedEvent` (for chat list ordering) |
| `conversation_participants` | id, conversation_id FK, user_id, role (MEMBER/ADMIN), joined_at, left_at, active | unique(conversation_id, user_id); `left_at` keeps history rules ("removed members cannot see future messages") |
| `private_conversation_keys` | conversation_id FK, user_low_id, user_high_id | unique(user_low_id, user_high_id) so a 1:1 pair has only one conversation |
| `group_settings` | conversation_id PK/FK, name, description, image_url, only_admins_can_send, only_admins_can_edit | |
| `conversation_mutes` | id, conversation_id FK, user_id, muted_until | |
| `conversation_archives` | id, conversation_id FK, user_id, archived_at | |
| `conversation_pins` | id, conversation_id FK, user_id, pinned_at | |
| `outbox_events`, `processed_events` | | |

Indexes: `conversation_participants(user_id, active)`, `conversations(last_message_at)`.

## What Phase 3 actually created
- **conversation_db:** `conversations` (id, type, created_by, created_at, last_message_at), `conversation_participants`, `private_conversation_keys` (unique pair), `outbox_events`, `processed_events`. Group, mute, archive and pin data came with Phase 4 (below).
- **message_db:** `messages` (id UUIDv7, conversation_id, sender_id, type, content, idempotency_key, created_at; unique (sender_id, idempotency_key); index (conversation_id, created_at, id)), `message_receipts` (unique (message_id, recipient_id, device_id)), `conversation_members` (our copy of membership, see ARCHITECTURE.md 3b), `outbox_events`. Columns for reply, edit, delete, attachments and the other tables below arrive with their features.

## What Phase 6 adds
- **Redis (presence-service):** `presence:devices:{userId}` = ZSET (member = device id, score = expiry in epoch millis; the key also gets a TTL), `presence:lastactive:{userId}` = epoch millis (TTL 30 days). No MySQL.
- **notification_db:** `notifications` (id, user_id, type, title, body, conversation_id nullable, count, is_read, unread_key nullable, created_at, updated_at; **unique (unread_key)**: `unread_key` is `userId:type:conversationId` while the notification is unread and NULL once read, so "one unread notification per chat" is enforced by the database and can be upserted with `INSERT ... ON DUPLICATE KEY UPDATE count = count + 1`), `notification_preferences` (user_id PK, push_enabled, messages_enabled, groups_enabled, calls_enabled), `device_tokens` (id, user_id, token unique, platform, created_at, last_seen_at), `notification_delivery_logs` (id, notification_id FK, channel PUSH, status PENDING / SENT / FAILED / DEAD, attempts, last_error, next_attempt_at, created_at, updated_at), `processed_events` (event_id PK).

## What Phase 5 adds
- **media_db:** `media_files` (id, owner_id, kind, original_name, mime_type [detected], size_bytes, object_key, status UPLOADED / ATTACHED / DELETED, attach_reference, created_at, attached_at, deleted_at; indexes on (status, created_at) for the cleanup job and on owner_id), `media_metadata` (media_id PK, width, height, duration_seconds, thumbnail_object_key, thumbnail_size_bytes), `media_access_logs` (id, media_id, user_id, action UPLOAD / VIEW_LINK / DOWNLOAD_LINK / ATTACH / DELETE, created_at). Object keys are random (`yyyy/MM/<uuid>`), never user names.
- **message_db:** `message_attachments` (message_id PK + FK, media_id, kind, file_name, mime_type, size_bytes, width, height, duration_seconds, has_thumbnail): a snapshot of the metadata so history needs no call to media-service.

## What Phase 4 adds
- **conversation_db (V2):** `group_settings` (conversation_id PK/FK, name VARCHAR(100), description VARCHAR(500) NULL, image_url VARCHAR(500) NULL, only_admins_can_send, only_admins_can_edit, updated_at). `conversation_participants` gets `muted_until`, `archived_at`, `pinned_at`: per-user chat settings live on the participant row, because one row per user and chat already exists and separate mute/archive/pin tables would only add joins. `membership_periods` (id, conversation_id FK, user_id, joined_at, left_at; index (conversation_id, user_id)) has one row per membership period and is back-filled from the participants. `conversation_participants` stays the *current* state (one row per user and chat, re-used when someone is added again); `membership_periods` is the history that decides which messages a member may see.
- **message_db (V2):** `conversation_members` becomes a copy of the membership periods: unique key `(conversation_id, user_id, joined_at)` instead of `(conversation_id, user_id)`. It also stores `conversation_type` (PRIVATE / GROUP; older rows are PRIVATE) so read-receipt privacy can tell groups from private chats without asking conversation-service. It is filled from `conversation.events` and from conversation-service's internal answers and never moves backwards (a closed period is never re-opened, nothing is deleted). Every query that returns messages to a user requires one of that user's periods with `joined_at <= created_at AND (left_at IS NULL OR created_at <= left_at)`. New `processed_events` table for the Kafka consumer. `SYSTEM` messages are stored in `messages` like any other message (content = JSON text).
- Times written by conversation-service are truncated to microseconds, so events, internal answers and both databases carry identical values.

## message_db (message-service, full design)

| Table | Key columns | Notes |
|---|---|---|
| `messages` | id (UUIDv7), conversation_id, sender_id, type, content, attachment_id, reply_to_id, forwarded, edited, deleted, deleted_at, idempotency_key, created_at(6), updated_at | unique(sender_id, idempotency_key) |
| `message_attachments` | id, message_id FK, media_id, file_name, mime_type, size_bytes, thumbnail_media_id | metadata only; file lives in media-service |
| `message_receipts` | id, message_id FK, recipient_id, device_id, status (DELIVERED/READ), delivered_at, read_at | unique(message_id, recipient_id, device_id) |
| `message_reactions` | id, message_id FK, user_id, emoji, created_at | unique(message_id, user_id) |
| `message_edit_history` | id, message_id FK, old_content, edited_at | content is private data; never logged |
| `message_pins` | id, message_id FK, conversation_id, pinned_by, pinned_at | |
| `message_deletions` | id, message_id FK, user_id, deleted_at | "delete for me" |
| `conversation_members_cache` | conversation_id, user_id, joined_at, left_at | optional local copy fed by events (only if REST+cache proves too slow) |
| `outbox_events` | id, aggregate_id, event_type, payload (JSON), created_at, published, published_at | scheduled publisher marks `published` |
| `processed_events` | event_id (PK), processed_at | consumer idempotency |

**Required indexes**
- `messages(conversation_id, created_at DESC, id DESC)` - history pages
- `messages(id)` - PK
- `messages(sender_id, idempotency_key)` - unique
- `message_receipts(message_id)`, `message_receipts(recipient_id, status)`
- `outbox_events(published, created_at)`

**Deletion visibility rules**
- *Delete for me*: row in `message_deletions`; hidden only for that user.
- *Delete for everyone*: sender only, within a time window (default 60 min); sets `deleted=true`, content cleared, others see "This message was deleted".
- Hard purge of old soft-deleted rows is a scheduled job (later).

**Receipt rules**
- SENT is implied by the existing `messages` row.
- `DELIVERED` row is created when a recipient device acks receipt; `READ` upgrades it. Status can only move forward.
- 1:1 message status = receipt status of the other user. Group message status = DELIVERED/READ when **all** active recipients reached it; per-participant details are visible to the sender.

## media_db (media-service)

| Table | Key columns |
|---|---|
| `media_files` | id, owner_id, bucket, object_key, original_name, mime_type, size_bytes, visibility (PRIVATE/PUBLIC), status (UPLOADED/ORPHANED/DELETED), created_at, expires_at |
| `media_metadata` | media_id PK/FK, width, height, duration_seconds, thumbnail_object_key |
| `media_access_logs` | id, media_id FK, user_id, action (UPLOAD/DOWNLOAD), created_at |

## notification_db (notification-service)

| Table | Key columns |
|---|---|
| `notifications` | id, user_id, type, title, body, data_json, read, created_at |
| `notification_preferences` | user_id PK, push_enabled, email_enabled, message_enabled, group_enabled, call_enabled |
| `device_tokens` | id, user_id, token, platform, last_seen_at (unique token) |
| `notification_delivery_logs` | id, notification_id FK, channel, status, attempt, error, created_at |
| `processed_events` | event_id PK |

## What Phase 7 adds
- **call_db (V1, call-signaling-service):** `calls` (id, caller_id, callee_id, type AUDIO / VIDEO, status RINGING / ACCEPTED / ENDED / MISSED / REJECTED / BUSY, end_reason, caller_device_id, callee_device_id (null until accepted), created_at, answered_at, ended_at, last_caller_ping_at, last_callee_ping_at, updated_at; indexes (caller_id, created_at), (callee_id, created_at), (status, created_at)); `call_slots` (user_id PK, call_id, created_at): **one row per user who is in a call right now**: inserting it is the atomic busy check, and it is deleted when the call reaches a final state; `outbox_events`.
- Not created (planned below, not needed for one-to-one calls): `call_participants` (always exactly caller + callee, already columns of `calls`) and `call_history` (the history is a query over `calls` by caller_id / callee_id). They can come back with group calls.
- **notification_db:** no schema change; the text column `notifications.type` gains the value `MISSED_CALL`.

## call_db (call-signaling-service, original plan)

| Table | Key columns |
|---|---|
| `calls` | id, caller_id, callee_id, type (AUDIO/VIDEO), status (RINGING/ACCEPTED/REJECTED/MISSED/ENDED/BUSY/FAILED), started_at, answered_at, ended_at |
| `call_participants` | id, call_id FK, user_id, role (CALLER/CALLEE), joined_at, left_at |
| `call_history` | id, user_id, call_id FK, direction (INCOMING/OUTGOING), outcome, duration_seconds |

## What Phase 8 adds
- **status_db (V1, status-service):** `statuses` (id UUIDv7, owner_id, type TEXT / IMAGE / VIDEO, text TEXT NULL, background_color VARCHAR(7) NULL, caption VARCHAR(700) NULL, media_id NULL, media snapshot: mime_type, width, height, duration_seconds, has_thumbnail; idempotency_key, created_at, expires_at; unique (owner_id, idempotency_key); indexes (owner_id, expires_at), (expires_at)); `status_views` (id, status_id FK ON DELETE CASCADE, viewer_id, viewed_at; unique (status_id, viewer_id); index (viewer_id)); `status_privacy` (owner_id PK, mode, updated_at); `status_privacy_members` (owner_id, user_id; PK both) = the excepted or chosen people of the current mode.
- The database user `status_user` and `status_db` are created by `infra/mysql/init` on a **new** MySQL volume; an existing volume needs them created once by hand (DOCKER_SETUP.md).
- No outbox: status-service publishes no events in Phase 8.

## status_db (status-service, original plan)
`statuses` (id, owner_id, type, content, media_id, expires_at), `status_views` (id, status_id FK, viewer_id, viewed_at), `status_privacy` (owner_id, mode, excluded/included ids).

## Redis (presence-service, shared helpers)

| Key | Value | TTL |
|---|---|---|
| `presence:device:{userId}:{deviceId}` | connection id / timestamp | ~90s, refreshed by heartbeat |
| `presence:lastseen:{userId}` | ISO timestamp | none |
| `typing:{conversationId}:{userId}` | 1 | ~5s |
| `ratelimit:{key}` | token bucket state | per policy |
| `auth:revoked:{sessionId}` | 1 | access token lifetime |
| `cache:participants:{conversationId}` | list of user ids | ~60s |
| `cache:blocked:{a}:{b}` | boolean | ~60s |
| pub/sub channel `ws.events` | JSON events for cross-instance fan-out | n/a |

## Local MySQL initialization
`infra/mysql/init.sql` creates the 7 databases and 7 users (`auth_user`, `user_user`, ...), each limited to its own database. Passwords come from `.env`, never committed.
