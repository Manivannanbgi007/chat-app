# notification-service

In-app notifications and push messages. Fed by Kafka events (`message.events`, `conversation.events`, and since Phase 7 `call.events`), read through REST. Design: `ARCHITECTURE.md` (notification-service), `KAFKA_EVENT_DESIGN.md`, `WEBSOCKET_DESIGN.md` (live events), `API_DOCUMENTATION.md`.

- Port: 8206
- Uses: MySQL (own database), Kafka, Redis (live events), and over REST (header `X-Internal-Token`): presence-service, conversation-service, user-service
- Build and test: `mvn test` (needs Docker: Testcontainers starts MySQL, Kafka and Redis; the three peer services are replaced by small fake HTTP servers)
- Run (IDE mode, from this folder): `mvn spring-boot:run -Dspring-boot.run.profiles=dev`
- Docker image: `docker build -t chat-notification-service .`
- Swagger UI: http://localhost:8206/swagger-ui.html

## Endpoints (all need a user JWT; every answer is only about the caller)
| Method | Path | Notes |
|---|---|---|
| GET | `/api/notifications?page=0&size=20` | newest activity first; size 1 to 50; `{ items, page, size, totalItems, hasMore }` |
| GET | `/api/notifications/unread-count` | `{ unreadCount }` (the bell) |
| PUT | `/api/notifications/{id}/read` | someone else's id is a 404, like an unknown id |
| PUT | `/api/notifications/read-all` | `{ updated }` |
| GET / PUT | `/api/notifications/preferences` | `{ pushEnabled, messagesEnabled, groupsEnabled, callsEnabled }`; a new user has everything on; PUT needs all four |
| POST | `/api/notifications/device-tokens` | `{ token (10..4096 chars), platform: WEB \| ANDROID \| IOS }`; a token that belonged to another user moves to the caller |
| DELETE | `/api/notifications/device-tokens?token=...` | removes only my own token (call it on logout) |

A notification looks like `{ id, type, title, body, conversationId, count, read, createdAt, updatedAt }` with `type` = `NEW_MESSAGES`, `GROUP_ADDED`, `GROUP_REMOVED`, `GROUP_ADMIN` or `MISSED_CALL` (Phase 7; `conversationId` is null).

## What creates a notification
| Event | Who gets it | Text |
|---|---|---|
| `MessageCreatedEvent` (type is not SYSTEM) | every recipient (not the sender) who is **offline**, did not **mute** the chat and wants it (`messagesEnabled` for private chats, `groupsEnabled` for groups) | title = sender (private) or group name; body = `1 new message`, `3 new messages` |
| `MessageReadEvent` | the reader's unread "new messages" notification of that chat is marked read | |
| `ConversationCreatedEvent` (GROUP) | everybody except the creator | `<creator> added you` |
| `GroupMembersAddedEvent` | the added members (not the actor) | `<actor> added you` |
| `GroupMemberRemovedEvent` (reason REMOVED) | the removed member (`LEFT` notifies nobody) | `<actor> removed you` |
| `GroupRoleChangedEvent` (role ADMIN) | that member | `You are now an admin` |
| `CallEndedEvent` (topic `call.events`, outcome `MISSED` or `BUSY`) | the callee, online or offline, if `callsEnabled` | title = caller's name; body = `Missed voice call` / `Missed video call`. `ANSWERED` / `REJECTED` notify nobody |

Group changes are created even for online users (they change what you can do) when `groupsEnabled` is on; so are missed calls (when `callsEnabled` is on). **The text of a message never reaches this service**: the events do not carry it, and the texts above only say who and how many.

### Aggregation: "3 new messages" is one notification
`notifications.unread_key` is UNIQUE. A "new messages" notification has the key `userId:NEW_MESSAGES:conversationId` while unread, so the second message hits the unique key and MySQL runs `ON DUPLICATE KEY UPDATE item_count = item_count + 1` instead. The database does the counting, so two messages at the same moment are both counted. Reading the notification sets the key to NULL (many NULLs are allowed), so the next message starts a new notification. Group and missed-call notifications get a key with the event id in it: they never merge.

### One event, one transaction
Every handler: (1) skip if the event id is in `processed_events`; (2) ask presence / conversation / user-service **before** the transaction (one presence call per event, names cached 5 minutes); (3) in ONE transaction remember the event id, write the notifications and queue the pushes; (4) after the commit publish the live event. If a peer is down: presence -> everybody counts as offline, user-service -> "Someone"; conversation-service down -> the event is retried and then dead-lettered (we cannot tell whether the chat is muted). An unknown chat is ignored. Two events of one chat colliding in MySQL are simply tried again.

## Live events (the bell updates without polling)
After each commit the service publishes to the Redis channel `ws.events` the envelope `{ userIds, destination: "/queue/notifications", json }`; message-service forwards it to the user's sockets (`/user/queue/notifications`). The event is `{ type: "NOTIFICATION", eventId, conversationId, data: <the notification>, timestamp }`. A Redis problem never loses a notification; the app reads the unread count again when it reconnects.

## Push messages
A push row (`notification_delivery_logs`, status PENDING) is queued in the same transaction as the notification, only for users who are offline, have `pushEnabled` and a registered device. A new message while the previous push is still unsent does not queue a second one (it carries the latest text). `PushDeliveryJob` runs every 5 s (`chatapp.push.poll-ms`) and locks due rows with `FOR UPDATE SKIP LOCKED`, so several instances can run it:
- success -> SENT
- failure -> FAILED, tried again after 10 s, then 60 s (`chatapp.push.retry-delays-seconds`), and after the 3rd failed try (`chatapp.push.max-attempts`) -> DEAD
- the user removed all devices in the meantime -> SENT with `last_error = "no device tokens"`
- `last_error` stores the exception class only: an error text could contain a device token.

**The real push provider (FCM / APNs) is not connected.** `LoggingPushSender` only logs `user id + token count` (a warning at start-up says so). To go live, add a class implementing `PushSender` and remove the logging one.

## Failed events
Same pattern as the other consumers: 3 retries (1 s, 5 s, 25 s), then a copy in `<topic>.dlt` on the same partition; broken events (not JSON, missing ids) skip the retries. Retry timing can be shortened with `chatapp.kafka.retry-initial-ms` / `chatapp.kafka.retry-multiplier` (the tests do).

## Environment variables
Defaults in `application.yml` work in IDE mode (infra from `infra/docker-compose.infra.yml`); the `prod` profile has no defaults.

| Variable | Meaning |
|---|---|
| `NOTIFICATION_DB_URL`, `NOTIFICATION_DB_USERNAME`, `NOTIFICATION_DB_PASSWORD` | MySQL |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka (`kafka:29092` inside Docker) |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | Redis (live events) |
| `JWT_SECRET` | at least 32 characters, same value in every service and the gateway |
| `INTERNAL_SERVICE_TOKEN` | at least 24 characters, same value in every service |
| `CONVERSATION_SERVICE_URL`, `USER_SERVICE_URL`, `PRESENCE_SERVICE_URL` | the peers (defaults `http://localhost:8203`, `:8202`, `:8207`) |
| `SWAGGER_ENABLED` | `true` / `false` |

## Code map
```
controller/   NotificationController, PreferenceController, DeviceTokenController
service/      NotificationEventService (all the event rules), NotificationRules (the small decisions and texts),
              NotificationService (the REST side), PreferenceService, DeviceTokenService, NameLookup (5 min cache)
kafka/        MessageEventConsumer, ConversationEventConsumer, CallEventConsumer, EventReader (envelope parsing), InvalidEventException
client/       Presence / Conversation / User clients (REST, 2 s connect and 3 s read timeout)
push/         PushSender (the place for FCM / APNs), LoggingPushSender, PushDeliveryJob (queue + retries)
realtime/     LiveNotificationPublisher (Redis ws.events)
repository/   Spring Data + the two native queries (aggregation upsert, locked queue read)
```

## Tests
Run `mvn test` (Docker needed). Integration tests share one MySQL + Kafka + Redis and use random ids, so they never depend on an empty database. The push job is switched off in tests and called by hand, so a test decides when a push is sent.

| Test | What it proves |
|---|---|
| `MessageNotificationTest` | offline gets one, online / muted / sender get none, `messagesEnabled` vs `groupsEnabled`, 3 messages = one row saying 3, same event twice counts once, SYSTEM and unknown chats ignored, read marks read and the next message starts a new row, titles and fallbacks, peers down, one presence call per event, message text never stored or published (planted secret), live Redis event, 12 parallel events = one row with count 12 |
| `GroupNotificationTest` | created / added / removed (LEFT is silent) / admin (MEMBER is silent), creator and actor excluded, no aggregation, `groupsEnabled=false`, online users still notified, duplicate event, fallbacks, live event |
| `PushDeliveryTest` | rows only for offline + token + pushEnabled, SENT, all devices in one push, FAILED with 10 s then 60 s delays, DEAD on the 3rd failure and never sent again, recovery on retry, error text with a token never stored, tokens removed meanwhile, one push for several messages |
| `DeadLetterTest` | bad JSON / missing payload go to `.dlt`, a broken event does not block the next, conversation-service down -> retried then dead-lettered and not marked handled, unrelated event types ignored |
| `NotificationApiTest` | 401 everywhere, paging and order, unread count, read / read-all, someone else's id = 404, preferences defaults / update / validation, device token upsert / move / remove own only / validation |
| `NotificationRulesTest`, `NameLookupTest`, `EventReaderTest` | the rules, the name cache, the event parser |

## Known limits
- Push goes nowhere until a real provider is added (see above).
- Old notifications are never deleted (a clean-up job is a later step).
- `callsEnabled` is stored but nothing uses it yet (no call feature).
- While the user is online only group changes notify; a message notification is not created at all (the open chat shows it).
