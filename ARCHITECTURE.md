# ARCHITECTURE

## 1. Big picture

```
                               +------------------+
   Browser (React) ----------> |   api-gateway    |  JWT check, rate limit (Redis),
   REST + WebSocket            |  (Spring Cloud   |  CORS, correlation ID, routing
                               |     Gateway)     |
                               +--------+---------+
        /api/auth  /api/users,contacts  |  /api/conversations  /api/messages + /ws
              |           |             |            |               |
        +-----v--+  +-----v----+  +-----v------+ +---v-----------+   |
        |  auth  |  |   user   |  |conversation| |   message     |<--+
        | service|  | service  |  |  service   | |   service     |
        +---+----+  +----+-----+  +-----+------+ +--+-----+------+
            |            |              |           |     |
          auth_db      user_db     conversation_db  message_db   Redis (pub/sub fan-out)
            \____________\______________\_______ Kafka ________/
                               |                    |
          +--------------------+---------+----------+----------+
          |                    |         |                     |
   +------v-----+   +----------v--+  +---v--------+   +--------v-------+
   |   media    |   |  presence   |  |notification|   | call-signaling |
   |  service   |   |  service    |  |  service   |   |    service     |
   +-----+------+   +------+------+  +-----+------+   +--------+-------+
   media_db+S3 storage   Redis only     notification_db       call_db  (+ Coturn)
```
Later: `status-service` (Phase 8).

Rules of the picture:
- The browser only talks to the **gateway**. Internal services are not public.
- **REST** = synchronous requests. **Kafka** = asynchronous domain events. **WebSocket** = live delivery to browsers. **Redis** = presence, rate limit, cache, cross-instance fan-out.
- Kafka never replaces live WebSocket delivery.

## 2. Technology

| Layer | Choice |
|---|---|
| Language/build | Java 21, Maven (one `pom.xml` per service) |
| Framework | Spring Boot (latest stable, pinned in Phase 1), Spring Security, Spring Data JPA, Spring Web, Spring WebSocket (STOMP), Spring Kafka, Spring Cloud Gateway |
| Libraries | Lombok, MapStruct, Bean Validation, Flyway, springdoc-openapi, Resilience4j (gateway + REST clients), Micrometer |
| Data | MySQL (db per service), Redis, Kafka (JSON), S3-compatible storage (RustFS locally, AWS S3 in production; MinIO no longer publishes Docker images) |
| Frontend | React, TypeScript, Vite, Tailwind, React Router, Axios, Zustand, TanStack Query, STOMP.js (+ SockJS if needed), React Hook Form, Zod, Lucide, react-virtuoso |
| Testing | JUnit 5, Mockito, Spring Boot Test, Testcontainers; Vitest, React Testing Library, Playwright |
| Infra | Docker, Docker Compose, MailHog (dev email), Kafka UI; Prometheus, Grafana, Loki, Promtail (Phase 9); Caddy (HTTPS, Phase 9); ClamAV (Phase 9); Coturn (Phase 7); k6 (load tests) |

## 3. Key decisions

1. **Independence.** Each service owns `pom.xml`, `Dockerfile`, config, Flyway migrations, tests, README. Event/DTO classes needed by two services are **copied**, not shared (a shared library would couple builds). Contracts are documented in `KAFKA_EVENT_DESIGN.md` and `API_DOCUMENTATION.md`.
2. **Local databases.** One MySQL container, seven databases, seven DB users (each service can only see its own database). In production each is its own instance.
3. **WebSocket placement.** Chat STOMP endpoint `/ws` lives in **message-service**. Calls use `/ws/calls` in **call-signaling-service**. The gateway routes by path.
4. **Multi-instance real-time.** Each message-service instance has an in-memory STOMP broker for *its own* sockets. When an event must reach a user, it is published on a Redis pub/sub channel; every instance receives it and pushes to the users connected to that instance.
5. **Message send flow (transactional outbox).**
   ```
   client -> POST /api/messages (or STOMP /app/chat.send)
     1. validate, check participant + block rules
     2. check idempotency key (return existing message if seen)
     3. ONE DB transaction: INSERT message + INSERT outbox_event
     4. return SENT ack to sender
     5. outbox publisher (scheduled) -> Kafka (key = conversationId)
     6. message-service pushes via Redis fan-out -> recipient sockets
     7. notification-service consumes event -> push to offline users
     8. recipient app acks DELIVERED, later READ -> receipts updated -> events
   ```
6. **Cross-service checks.** message-service asks conversation-service "is user a participant?" and user-service "is either user blocked?" over REST, with a short Redis cache (TTL ~60s) that is also invalidated by Kafka events (`GroupMemberRemoved`, `UserBlocked`). Circuit breaker + timeout on these calls.
7. **Ordering and pagination.** Server timestamps (UTC, microseconds) + UUIDv7 ids. Keyset cursor `(created_at, id)`; never OFFSET.
8. **Receipts.** One row per (message, recipient user, device). Message-level status is derived. Read receipts are hidden when privacy says so (applies both ways, as in WhatsApp).
9. **Presence.** Client heartbeat (~30s) through the gateway refreshes a Redis key per device with a TTL (~90s). User is online if any device key exists. No MySQL for live presence.
10. **Gateway** contains routing and cross-cutting filters only; no business logic. Reactive (WebFlux), but kept configuration-only so little reactive code exists.
11. **Auth propagation.** The gateway validates the JWT (signature, issuer, expiry, and a Redis check that the login session was not ended), then forwards the **original token unchanged**. Each downstream service validates the token again with the same shared secret (defence in depth) and reads the user id from the token's `sub`. No `X-User-Id` header is used, so there is only one identity channel. Services **never trust a user id from the request body or path for authorization**. Phase 2 uses HS256 with a shared `JWT_SECRET`; moving to RS256 (only auth-service holds the private key) is planned for Phase 9.

### 3b. Decisions made while building Phase 3 (these replace the earlier plan where they differ)
- **No cache for access checks.** Every send asks conversation-service ("is the sender a participant?") and user-service ("is either user blocked?"). That costs two small HTTP calls but means a block or a removal works **immediately**. A Redis cache with event invalidation (`UserBlocked`, `GroupMemberRemoved`) can be added if load tests show the calls are a bottleneck. The calls happen before the database transaction, with 2 s / 3 s timeouts, and a failure becomes `503 DEPENDENCY_UNAVAILABLE`.
- **Internal calls** use the header `X-Internal-Token` (one shared `INTERNAL_SERVICE_TOKEN`) on `/internal/**` endpoints that the gateway never routes. A user JWT does not open them and the internal token does not open `/api/**`.
- **message-service keeps a small copy of "who is in which conversation"** (`conversation_members`, filled from conversation-service's answers on every send). It is used only to find a user's conversations (unread counts, sync). It is never used to decide whether someone may read or write.
- **Message text stays in message-service.** Events carry ids and times only. The chat list merges conversation-service's list with message-service's `summaries` by conversation id.
- **Sync cursor.** A cursor is `(createdAt, id)`. When a client has caught up, the cursor handed back is moved 10 seconds back, so a message whose transaction committed slightly late is found next time; clients de-duplicate by id. While more pages exist the cursor is exact, so paging always moves forward.
- **Receipts.** One row per (message, recipient, device) where device = login session id. Status is derived: no row = SENT, any row = DELIVERED, READ if any READ row **and** both users allow read receipts (checked when the status is shown, so changing the setting applies to old messages too).
- **WebSocket path:** browser -> nginx / Vite proxy -> gateway (`/ws` is open at the gateway; the token is checked in the STOMP CONNECT frame) -> message-service. The WebSocket route has no circuit breaker and no time limit (a socket lives for hours); every HTTP route has its **own** circuit breaker so one broken service cannot block the others.
- **Multi-instance:** any instance can receive an event, publish it to the Redis channel `ws.events`, and every instance pushes it to its own sockets. Verified with two real instances (TESTING_GUIDE.md section 6).

### 3c. Decisions made for Phase 4 (groups)
- **Membership periods.** conversation-service keeps the current state in `conversation_participants` (role, active, mute / archive / pin) and every membership period in `membership_periods`. A member may read the messages created inside their periods `[joinedAt, leftAt]`; this is the removed-member rule, and it also means new members do not see older messages and a member who is added again does not see the gap. message-service applies it on every read (history, sync, summaries, read, delivered, receipt details) using its copy of the periods.
- **The copy of the periods in message-service** is filled from three places that all carry the exact times from conversation-service: `conversation.events` (normally within a second), the internal answer on every send, and the internal answer on every history request. The copy only moves forward (a closed period is never re-opened), so an old answer arriving late cannot undo a removal. Because every send refreshes it before saving, a message sent after a removal can never fall inside the removed member's period. Remaining race: a send that passed the membership check just before a removal is still delivered live to the removed member, but it is outside their period, so it disappears from their history on reload.
- **Group changes become SYSTEM messages.** conversation-service publishes `GroupMembersAdded / GroupMemberRemoved / GroupRoleChanged / GroupUpdated` (outbox, key = conversationId, so they stay in order); message-service turns them into SYSTEM messages with the time of the change and pushes them live. The browser refreshes a conversation when a SYSTEM message for it arrives, so member lists, roles, names and settings update live without a second WebSocket event type. No synchronous call goes from conversation-service to message-service (no cycle).
- **Group-mutating requests lock the conversation row** (`SELECT ... FOR UPDATE`) so two admins acting at once cannot exceed the size limit, create two periods for one person or leave a group without an admin. Calls to user-service (group-add check, names) happen before the transaction.
- **Blocking does not apply inside groups** (as in WhatsApp); it only stops private messages and adding each other to groups. This also keeps a group send at one call to conversation-service instead of one block check per member.
- **Group receipts:** a group message is DELIVERED / READ when every recipient (members at send time who are still members) reached that state. Read receipts are always shown in groups; the privacy switch applies to private chats (as in WhatsApp).
- **Typing** goes to each current member's `/user/queue/typing` instead of a per-conversation topic, because a topic subscription would keep working after the member was removed.
- **Mute, archive and pin** are columns on the participant row (they are per user and per chat). Mute only changes how the chat list looks until notifications arrive in Phase 6.

### 3d. Decisions made for Phase 5 (media)
- **Upload path:** browser -> gateway -> media-service (multipart) -> object storage. The file is validated before it is stored (size, real content type from the file signature, image decoding and dimension limit, name cleaned). Files never go through message-service and never into MySQL; only metadata and the object key are stored (`media_db`).
- **Two-step sending:** upload first (gets a `mediaId`, owner = uploader), then send a message with `attachmentId`. message-service asks media-service to **attach** the file (internal call, owner must be the sender, kind must match, one message per file, idempotent per `senderId:idempotencyKey`) **before** saving the message. If saving then fails, the file stays attached to nothing (a few wasted bytes, never a security problem); files that are never attached are deleted after 24 h.
- **Who may download:** the rule belongs to the **message**, not the file. message-service (which knows the removed-member rule) checks that the viewer may see the message, then asks media-service for a signed link. So media-service does not need to know about conversations, and there is no circular call (message -> media only). The uploader may also fetch their own unattached upload (preview).
- **Signed links go straight to the storage** (5 minutes). The link is signed for the **public** storage address (`S3_PUBLIC_ENDPOINT`, `http://localhost:9010` in Docker) while the service itself uploads through the **internal** address (`http://s3:9000`). Documents are served as attachments (download); content type and disposition are forced in the link so the storage cannot be tricked into serving an upload as HTML.
- **Message snapshot:** message-service stores a copy of the attachment's metadata (`message_attachments`) so history pages need no call to media-service.
- **Limits at the edges:** nginx `client_max_body_size` and the gateway media route (own circuit breaker with a 150 s limit and its own rate limit, 20 uploads per minute) allow 100 MB uploads; every other route keeps the 30 s limit.
- **Not built:** profile / group photos (they need public, long-lived image URLs; planned as a small follow-up), video thumbnails and durations (need ffmpeg), re-compression of originals, a real virus scanner (an interface exists, the default scans nothing), `MediaUploadedEvent` (nobody consumes it yet).

### 3e. Decisions made for Phase 6 (presence and notifications)
- **Presence = heartbeats, nothing else.** The app calls `POST /api/presence/heartbeat` every 20 s; the presence-service keeps, per user, a Redis sorted set `presence:devices:{userId}` (device id -> expiry time, device = login session id) and `presence:lastactive:{userId}`. A device is alive for 60 s after its last heartbeat, so crashes and lost connections turn offline by themselves (TTL), and a user is online while **any** device is alive. Logout / closing the page calls `/offline` for an instant change. No MySQL, no Kafka, and no coupling to the WebSocket: the WebSocket tells us nothing a heartbeat does not.
- **No live presence push.** The open chat asks `GET /api/presence/{userId}` every 10 s (a single Redis read plus one privacy check). A push channel would need an audience and a privacy decision per change; polling one value is simpler and correct. Typing indicators stay in message-service (they are real-time and per conversation).
- **Privacy is decided by user-service** (`/internal/users/presence-visibility`): the presence-service never sees privacy settings, contacts or blocks.
- **Notifications are event driven.** notification-service reads `message.events` (new messages, reads) and `conversation.events` (group changes), idempotently (`processed_events`), with retry and a dead-letter topic. Everything it needs to decide is asked from the owner of the data: presence-service (is the user online?), conversation-service (muted? group name, type), user-service (display names). Message **text never** enters this service.
- **Offline users get message notifications** (online users already see the message in the app); one aggregated notification per chat while unread; marked read by `MessageReadEvent`. **Group changes** notify online and offline users alike.
- **Push is a plug.** Pushes are queued in `notification_delivery_logs` inside the same transaction that creates the notification and sent by a scheduled job with retry (10 s, 60 s, 5 min, then DEAD). The default `PushSender` writes a log line; a real FCM sender (needs Firebase credentials) is a later drop-in. In the browser, the app also shows a **local** browser notification for messages that arrive while the tab is hidden (permission asked by the user, nothing sent through any server).
- **Live in-app notifications** reuse the Redis channel `ws.events`: notification-service publishes the same envelope message-service uses, and message-service's subscriber delivers it to the user's sockets.

### 3f. Decisions made for Phase 7 (calls)
- **One socket, REST up, Redis down.** The plan had a second WebSocket (`/ws/calls`) in call-signaling-service. Instead, the browser sends call messages with REST (`/api/calls/...`) and receives call events on the chat socket's `/user/queue/calls`: call-signaling-service publishes them to the Redis channel `ws.events`, exactly like notification-service does since Phase 6. This avoids a second socket per tab and a second copy of the WebSocket authentication, token-expiry checks and multi-instance fan-out. Signaling is a handful of small messages per call, so REST adds no noticeable delay.
- **Media never touches our servers.** Audio and video go browser to browser (WebRTC). When a direct path is impossible (strict NAT, firewalls) they are relayed by **Coturn** (TURN). Coturn uses the TURN REST scheme: call-signaling-service gives each participant a username `expiry:userId` and a password `base64(HMAC-SHA1(TURN_SECRET, username))`, valid 8 hours, so no TURN passwords are stored anywhere.
- **The server owns the call state** (`calls` table, a small state machine: RINGING -> ACCEPTED -> ENDED, or MISSED / REJECTED / BUSY). Every change locks the call row, so "accept" racing "cancel" or the ring timeout has one winner. **Busy** is a row per user in `call_slots` taken in the same transaction (a unique key, so two simultaneous calls cannot both get the same person).
- **Devices:** a call rings on all of the callee's devices; the first one that accepts becomes the call device; signals and pings are accepted only from the two call devices.
- **Lost clients are cleaned up by time:** ringing ends after 30 s (MISSED), and an accepted call ends when one side stops pinging for 30 s (CONNECTION_LOST), so a crashed browser never leaves a user "busy" for ever. A scheduled job does this on every instance with `FOR UPDATE SKIP LOCKED`.
- **Missed calls** go to Kafka (`CallEndedEvent`, outbox) and notification-service creates a `MISSED_CALL` notification (and a push when the callee is offline).
- **Not built:** group calls (need an SFU), calls in the chat timeline, switching audio to video during a call, ringing push to a closed browser (push is still a plug, Phase 6).

### 3g. Decisions made for Phase 8 (status / stories)
- **status-service owns statuses, views and status privacy** (`status_db`). It needs only MySQL and REST calls: user-service for contacts, blocks, read-receipt settings and names; media-service for attaching, signing and deleting files. No Redis, no Kafka.
- **Audience = my contact list**, filtered by the status privacy mode (all contacts / contacts except... / only share with... (and must be a contact)) and by blocks either way. It is decided **when someone looks**, not stored per status, so removing a contact or blocking someone hides my statuses from them at once. (WhatsApp keeps the audience of statuses already sent; we chose the stricter, simpler rule.)
- **The feed is built per request:** user-service lists the users who have me as a contact (minus blocks); status-service keeps those whose privacy allows me and who have active statuses, and asks user-service for their profiles. Polled every 30 s while the Status page is open: no live push (statuses are not urgent, and an audience push would need the same computation for every post).
- **Viewers follow read receipts** like WhatsApp: views are always recorded, but only listed for the owner when the viewer has read receipts on, and only shown at all when the owner has them on.
- **Media reuse:** status photos and videos are normal uploads attached with the reference `status:<ownerId>:<idempotencyKey>`. Expired or deleted statuses **release** their file (a new internal media-service call that only works with that exact reference, so it can never delete a message's file).
- **24 h expiry** is a filter on every read (`expires_at > now`) plus a cleanup job that deletes the rows and the files (a file that cannot be deleted right now is tried again on the next run).
- **Not built:** replies to a status (they need message replies), muting someone's statuses, text fonts, video trimming (videos keep the media-service size limits), status notifications.

### 3h. Decisions made for Phase 9 (production readiness)
- **One server, Docker Compose.** Production is the same Compose file plus overlay files (`docker-compose.prod.yml`, `.clamav.yml`, `.monitoring.yml`), not a different
  setup, so what was tested is what runs. Only Caddy (80/443) and Coturn are open to the internet. Kubernetes stays out of scope (see the checklist).
- **Caddy in front, straight to the gateway.** Caddy gives automatic HTTPS and sends `/api` and `/ws` directly to the gateway and the rest to the frontend's nginx. Going through
  nginx first would make the gateway see one proxy address for every user and break the per-IP login limit (SECURITY_DESIGN.md 7i, finding 7).
- **Monitoring = metrics + logs, pulled not pushed.** Every service exposes Micrometer metrics at `/actuator/prometheus` (inside the Docker network only; the gateway's actuator
  moved to the internal port 8290); Prometheus scrapes every 15 s and evaluates the alert rules; Loki + Promtail collect the container logs (level as a label, correlation id searchable
  in the line); Grafana shows one dashboard. Grafana and Prometheus listen on 127.0.0.1 and are reached through an SSH tunnel. No Alertmanager yet (rules are visible, notifications are the next step).
  One business metric was added: `websocket.sessions.active` (message-service).
- **Images:** multi-stage, Spring Boot layered jar (libraries and our classes in separate layers, so an update ships only the small application layer), a **jlink** Java runtime with
  only the needed modules on plain Alpine (about 35-40 % smaller than the full JRE image), non-root user, `apk upgrade` for OS fixes, BuildKit cache mounts for Maven / npm.
  Compose adds `cap_drop: ALL`, `no-new-privileges`, a read-only root file system and an in-memory `/tmp`. The frontend runs on the unprivileged nginx image.
- **Release = tag.** CI tests and scans on every push; a `v*` tag builds, scans and pushes the 11 images (provenance + SBOM); deployment (`deploy.sh`) backs up first, pulls, starts, waits for
  healthy and checks the site; rollback = deploy the previous tag. Deploying from CI is optional and needs a manual approval (GitHub environment `production`).
- **Backups are files you can verify.** Per-database `mysqldump --single-transaction` (no downtime), Redis snapshot, uploaded files, row counts and checksums, optional AES-256 encryption;
  `verify-backup.sh` restores into scratch databases and compares row counts. Kafka is not saved because the outbox tables hold every event that matters.
- **Load tests with k6 in Docker** against the real gateway, with real WebSocket connections and the "send to arrives on the other phone" time as the main number.
- **Not done:** RS256 tokens, HttpOnly refresh cookie, per-service internal tokens, Alertmanager, TURN over TLS, a second server (see SECURITY_DESIGN.md 7i and DEPLOYMENT_GUIDE.md section 9).

## 4. Design patterns used

| Pattern | Where |
|---|---|
| API Gateway | api-gateway |
| Database per service | all |
| Layered: Controller -> Service -> Repository, DTO + Mapper | all |
| Transactional Outbox | message-service (and others for critical events) |
| Idempotency key / Idempotent consumer | send message; every Kafka consumer (`processed_events` table) |
| Retry + Dead-letter topic | Kafka consumers |
| Strategy | `StorageService` (local S3-compatible / AWS S3), notification channels (push/email) |
| State machine | receipts, call state, OTP state |
| Refresh-token rotation | auth-service |
| Cache-aside + TTL lease | participant/block cache, presence |
| Circuit breaker + timeout | gateway, REST clients |
| Token bucket | rate limiting (Redis) |
| Soft delete | messages |
| Optimistic UI + rollback | frontend |

Intentionally **not** used: CQRS, event sourcing, hexagonal architecture, Avro, a shared library.

## 5. Standard service layout

```
<service>/
  src/main/java/com/chatapp/<service>/
    config/ controller/ dto/{request,response}/ entity/ enums/ exception/
    mapper/ repository/ service/{impl}/ kafka/{producer,consumer}/
    websocket/ security/ util/ constants/ <Service>Application.java
  src/main/resources/ application.yml, application-dev.yml, application-prod.yml, db/migration/
  src/test/java/... controller/ service/ repository/ integration/
  Dockerfile  pom.xml  README.md
```
Only create packages that the service actually needs.

## 6. Repository layout

```
chat-app/
  infra/        docker-compose.infra.yml (dev), docker-compose.yml (full), .env.example, mysql init, prometheus/grafana/loki configs
  services/     api-gateway/ auth-service/ user-service/ conversation-service/ message-service/ ...
  frontend/
  *.md          design documents
```

## 7. Run modes
- **IDE mode:** `docker compose -f infra/docker-compose.infra.yml up` starts only MySQL, Redis, Kafka, Kafka UI, S3 storage, MailHog; services run from the IDE with the `dev` profile.
- **Full mode:** `docker compose -f infra/docker-compose.yml up --build` starts infra + every service + frontend using service DNS names.
- Host ports are non-default to avoid clashes with other local projects (see `DOCKER_SETUP.md` when written).

## 8. Service ports (proposed)

| Service | Port |
|---|---|
| api-gateway | 8200 |
| auth-service | 8201 |
| user-service | 8202 |
| conversation-service | 8203 |
| message-service | 8204 |
| media-service | 8205 |
| notification-service | 8206 |
| presence-service | 8207 |
| call-signaling-service | 8208 |
| status-service | 8209 |
| frontend (Vite dev) | 5210 (5200 is used by another local project) |
| frontend (Docker) | 5280 |

Infrastructure host ports: MySQL 3330, Redis 6391, Kafka 9094, Kafka UI 8290, S3 API 9010 / console 9011, MailHog SMTP 1025 / UI 8025.
Pinned versions (Phase 1): Java 21, Spring Boot 4.0.9, Spring Cloud 2025.1.4 (the release train for Boot 4.0.x; Boot 4.1.x has no final Cloud release yet), springdoc 3.0.3.

## 9. Scaling and failure notes
- All services are stateless except live socket connections; run N instances behind the gateway.
- If Kafka is down, writes still succeed (outbox rows wait) and live delivery still works.
- If Redis is down, real-time cross-instance fan-out degrades; clients fall back to sync-on-reconnect via REST.
- Graceful shutdown enabled so in-flight requests finish.
