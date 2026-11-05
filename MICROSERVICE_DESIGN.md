# MICROSERVICE DESIGN

Package base: `com.chatapp.<service>` (e.g. `com.chatapp.message`). Each service is an independent Maven project under `services/`.

## 1. Service catalogue

| Service | Port | DB | Owns | Talks to (sync) | Publishes | Consumes |
|---|---|---|---|---|---|---|
| api-gateway | 8200 | - (Redis) | routing, rate limit, JWT check | all (proxy) | - | - |
| auth-service | 8201 | auth_db | credentials, tokens, sessions, OTP | - | UserRegistered | - |
| user-service | 8202 | user_db | profile, contacts, blocks, privacy | - | UserProfileUpdated, UserBlocked/Unblocked | UserRegistered |
| conversation-service | 8203 | conversation_db | conversations, members + membership periods, admins, group settings, mute/archive/pin | user-service (group-add check, blocks, profiles) | ConversationCreated, GroupMembersAdded, GroupMemberRemoved, GroupRoleChanged, GroupUpdated | MessageCreated (update last message) |
| message-service | 8204 | message_db | messages (incl. SYSTEM), receipts, reactions, pins, edits, WebSocket chat | conversation-service (members, admins, periods), user-service (blocks, privacy) | MessageCreated, MessageDelivered, MessageRead, MessageDeleted | conversation.events (membership periods + SYSTEM messages) |
| media-service | 8205 | media_db + S3 storage | files, thumbnails, links | - | - (no events yet) | - |
| notification-service | 8206 | notification_db | notifications, prefs, device tokens | presence-service (is user online?), conversation-service (muted, group name), user-service (names) | - | MessageCreated, MessageRead, ConversationCreated, GroupMembersAdded, GroupMemberRemoved, GroupRoleChanged (CallEnded in Phase 7) |
| presence-service | 8207 | Redis | online, last seen (multi-device, TTL) | user-service (privacy) | - | - |
| call-signaling-service | 8208 | call_db + Redis (publish) | calls, call state, busy slots, signaling relay, TURN credentials | user-service (profiles, blocks) | CallEnded | - |
| status-service (P8) | 8209 | status_db | statuses (24 h), views, status privacy | user-service (contacts, blocks, read receipts, names), media-service (attach, signed links, release) | - (StatusPosted not needed yet) | - |

## 2. Dependency rules
- No circular **synchronous** calls. Allowed direction: message -> conversation -> user; presence -> user; notification -> presence.
- If A needs data owned by B often, prefer a **cache** (Redis, TTL) fed by B's events. Never read B's database.
- Anything that can happen later (notifications, last-message preview, audit) goes through Kafka.

## 3. Responsibilities in detail

### api-gateway
Routes (strip nothing, path preserved):
```
/api/auth/**          -> auth-service         (public: register, login, refresh, verify-otp, forgot/reset)
/api/users/**         -> user-service
/api/contacts/**      -> user-service
/api/conversations/** -> conversation-service  (except /{id}/messages -> message-service)
/api/messages/**      -> message-service
/api/media/**         -> media-service
/api/notifications/** -> notification-service
/api/presence/**      -> presence-service
/api/calls/**         -> call-signaling-service
/ws/**                -> message-service  (also carries call events since Phase 7; no /ws/calls)
```
Filters (in order): correlation ID -> JWT validation (public paths skipped; also checks the Redis "session revoked" key) -> rate limit (per IP on auth routes, per user elsewhere) -> circuit breaker + timeout. The original `Authorization` header is forwarded unchanged (no `X-User-Id`). CORS configured centrally. Actuator health.

### auth-service
Register -> save `auth_users` (BCrypt) -> outbox `UserRegisteredEvent` -> send verification OTP/email. Login issues access JWT (15 min) + refresh token (7 days, stored hashed, rotated). Brute-force protection: lock after N failures. OTP: hashed, 5 min expiry, max resends per hour. Logout revokes session + writes Redis revocation key.

### user-service
Creates profile/privacy defaults when it consumes `UserRegisteredEvent` (idempotent). Exposes block and privacy lookups for other services (`GET /internal/...` not routed by gateway).

### conversation-service
Creates PRIVATE conversation once per pair (unique key). Group permission checks live here (only ADMIN can add/remove members, change roles and settings; renaming follows `onlyAdminsCanEdit`). Keeps one row per membership period for the removed-member rule. Exposes internal `GET /internal/conversations/{id}` (members, admins, periods).

### message-service
Layers: `controller` (REST), `websocket` (STOMP controllers + auth interceptor), `service` (send, history, edit, delete, react, forward, receipts), `repository`, `kafka/producer` (outbox publisher), `kafka/consumer`, `config`. Single owner of message correctness rules (see ARCHITECTURE section 3.5-3.8 and DATABASE_DESIGN).

### media-service
Validates (size limit, MIME allow-list, magic-number check), stores via `StorageService` interface (`MinioStorageService`, `S3StorageService` later), saves metadata, generates thumbnail for images, returns pre-signed URLs with short expiry after checking the caller may access the media. `VirusScanner` interface with a no-op implementation until production.

### notification-service
Consumes events, checks preferences and presence, creates a `notifications` row, sends via channel strategies (`PushChannel` using FCM later, `EmailChannel`). Retry with backoff, then dead-letter topic. De-duplicates with `processed_events`.

### presence-service
`POST /api/presence/heartbeat` (device id), WebSocket connect/disconnect events from message-service via Redis pub/sub, TTL expiry, last-seen write on offline. Typing state with 5s TTL. Applies privacy before returning `GET /api/presence/{userId}`.

### call-signaling-service
State machine: RINGING -> ACCEPTED -> ENDED; RINGING -> REJECTED / MISSED (30 s or cancelled); BUSY at once. Receives SDP offer/answer and ICE candidates by REST and relays them to the other call device through Redis `ws.events` and message-service's socket (`/user/queue/calls`); media flows peer-to-peer via WebRTC (TURN through Coturn when needed). Ring and ping time-outs run as a scheduled job.

## 4. Common conventions (every service)
- `ApiResponseDTO<T>` `{success, message, data, timestamp}` for all REST responses.
- `@RestControllerAdvice` global exception handler -> consistent error body + correct status.
- `CorrelationIdFilter`: reads `X-Correlation-Id` (or creates one), puts in MDC and response header, copies into Kafka headers.
- Validation with Bean Validation on request DTOs.
- Swagger UI at `/swagger-ui.html` (dev profile only).
- Actuator `health`, `info`, `prometheus`.
- Profiles: `dev` (localhost infra, IDE) and `prod` (env vars, Docker DNS names). No secrets in files; everything via environment variables with `.env.example`.
- Kafka event envelope: see `KAFKA_EVENT_DESIGN.md`.
- Never log passwords, tokens, OTPs, message content.

## 5. Build order
Phase 1: infra + api-gateway + shared conventions proven on a thin auth-service.
Phase 2: auth, user. Phase 3: conversation (PRIVATE), message. Phase 4: groups. Phase 5: media. Phase 6: presence, notification. Phase 7: call-signaling. Phase 8: status. Phase 9: hardening.
