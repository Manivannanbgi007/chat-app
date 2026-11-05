# message-service

The most important service: it saves messages safely and delivers them live. Text messages, private chats and groups, history with cursors, delivered/read receipts per device (combined for groups, with a per-recipient "message info"), unread counts, sync after reconnecting, typing indicators, SYSTEM messages for group changes, and the WebSocket (STOMP) endpoint `/ws`. API: `API_DOCUMENTATION.md` section 5. WebSocket: `WEBSOCKET_DESIGN.md`. Events: `KAFKA_EVENT_DESIGN.md`.

- Port: 8204 | Database: `message_db` (user `message_user`)
- Uses: MySQL, Redis (live fan-out between instances, revoked-session check), Kafka (outbox -> `message.events`; reads `conversation.events`), conversation-service and user-service over REST (internal token)
- Build and test: `mvn test` (needs Docker: Testcontainers starts MySQL, Redis and Kafka; conversation-service and user-service are replaced by small fakes)
- Run (IDE mode, from this folder): `mvn spring-boot:run -Dspring-boot.run.profiles=dev`
- Docker image: `docker build -t chat-message-service .`
- Swagger UI: http://localhost:8204/swagger-ui.html

## How sending works
1. Same `idempotencyKey` already saved by this user? Return that message (retries never duplicate).
2. Ask conversation-service: is the sender a **current** member? (404 if not, also for someone who left a group.) Group with "only admins can send" and the sender is no admin: 403 `ONLY_ADMINS_CAN_SEND`. Private chat only: ask user-service whether either user blocked the other (403). Blocking does not apply inside groups.
3. One database transaction: save the message (server time) **and** queue the Kafka event in the outbox.
4. After the commit: publish the message to the Redis channel `ws.events`; every instance pushes it to the sockets of the current members connected to it.
5. The caller gets the answer only after step 3, so an acknowledged message is always saved.

## Groups (Phase 4)
- **Membership periods.** `conversation_members` holds one row per (conversation, user, joined_at) with `left_at` (null = still a member). It is a copy: filled from conversation-service's internal answer (every time we ask it) and from `conversation.events`. It only moves forward: a period is added or closed, never re-opened or deleted, so a slightly older answer cannot undo a removal.
- **Removed-member rule.** Every query that returns messages to a user (`MessageRepository.VISIBLE_TO_USER`) requires one of the user's periods to cover the message time (`joined_at <= created_at <= left_at`). New members do not see older messages; removed members keep their old ones but see nothing newer; someone added again sees both periods but not the gap. Reading needs a current or former membership (`requireReader`), writing a current one (`requireMember`).
- **Group status.** Recipients of a group message = members at send time who are still members. `SENT` until all of them have it, `DELIVERED` when all do, `READ` when all read it (always shown in groups). The sender gets `RECEIPT_UPDATED` only when that combined status is DELIVERED or READ. `GET /api/messages/{id}/receipts` lists each recipient (sender only).
- **SYSTEM messages.** `ConversationEventConsumer` reads `conversation.events` (consumer group `message-service`). `GroupEventService` handles each event in one transaction: `processed_events` (duplicates do nothing), membership periods, the SYSTEM message(s) (content = JSON, time = time of the change, key `sys-<eventId>-<n>`), and a `MessageCreatedEvent`. After the commit it pushes them to the current members and to the person who was removed or left. Broken events go straight to `conversation.events.dlt`; other failures are retried 1 s / 5 s / 25 s first. SYSTEM messages never count as unread and are never acknowledged.
- **Typing** goes to `/user/queue/typing` of the current members (no more `/topic/conversation/{id}`: a topic subscription would outlive a removal).

## Events from other services on this socket
The socket also carries events that other services publish into the Redis channel `ws.events` (same envelope; the subscriber delivers any destination to the listed users): `/user/queue/notifications` (notification-service, Phase 6) and `/user/queue/calls` (call-signaling-service, Phase 7: `CALL_INCOMING`, `CALL_ACCEPTED`, `CALL_ENDED`, `CALL_SIGNAL`). Both are on the subscribe allow-list in `StompAuthChannelInterceptor`.

## Code map
```
controller/   MessageController (REST)
websocket/    WebSocketConfig (in config/), StompAuthChannelInterceptor (the security guard), ChatStompController (/app/chat.*),
              RealtimePublisher + RealtimeSubscriber (Redis fan-out), SessionExpiryChecker, SlidingWindowLimiter
service/      MessageService (send/history/sync/summaries), ReceiptService (acks + message info), MessageViewService
              (what each viewer sees), GroupStatusService (combined group status), ConversationAccessService
              (member / reader checks), MembershipService (our copy of the periods), GroupEventService +
              SystemMessageContent (conversation.events -> SYSTEM messages)
client/       ConversationClient, UserClient (REST to the other services)
kafka/        outbox publisher, event payloads (ids only, never message text), consumer of conversation.events
util/Cursor   opaque (createdAt, id) cursors
```

## Environment variables
Defaults in `application.yml` work in IDE mode; the `prod` profile has no defaults.
`MESSAGE_DB_URL`, `MESSAGE_DB_USERNAME`, `MESSAGE_DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `KAFKA_BOOTSTRAP_SERVERS`, `CONVERSATION_SERVICE_URL`, `USER_SERVICE_URL`, `INTERNAL_SERVICE_TOKEN`, `JWT_SECRET`, `CORS_ALLOWED_ORIGINS` (browser origins allowed to open a WebSocket), `SWAGGER_ENABLED`.

## Rules worth remembering
- The sender is always the token's `sub`; DTOs have no sender field.
- Order is `(createdAt, id)` everywhere; never use OFFSET paging.
- Do not call other services inside a database transaction.
- Every new query that returns messages to a user must include `VISIBLE_TO_USER`.
- Membership changes (`MembershipService`) run in their own short transaction with a retry, because two first messages in a new conversation collide on the same rows.
- Message text must never appear in Kafka events or logs.
- Known limit: a SYSTEM message is dated at the time of the change but written when the event is consumed. If Kafka is down for longer than the 10-second sync look-back, a client that synced in between does not get it through `sync` (it is still in the history and the chat list).
