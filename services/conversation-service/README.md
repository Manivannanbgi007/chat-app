# conversation-service

Who is talking to whom: one-to-one conversations (Phase 3), groups with members, admins and settings, and each user's own chat settings (mute, archive, pin) (Phase 4). It also answers other services' question "who takes part in conversation X, and since when?". **Message text is not stored here** (message-service owns it). The API is described in `API_DOCUMENTATION.md` (sections 4 and 8); the events in `KAFKA_EVENT_DESIGN.md`.

- Port: 8203 | Database: `conversation_db` (user `conversation_user`)
- Uses: MySQL, Kafka, user-service (REST, with `X-Internal-Token`)
- Build and test: `mvn test` (needs Docker: Testcontainers starts MySQL and Kafka; user-service is a fake in the tests)
- Run (IDE mode, from this folder): `mvn spring-boot:run -Dspring-boot.run.profiles=dev`
- Docker image: `docker build -t chat-conversation-service .`
- Swagger UI: http://localhost:8203/swagger-ui.html

## Endpoints
All `/api/**` endpoints need a user JWT; the caller is always the token's `sub`. Every answer that returns a chat returns it **as the caller sees it** (`Conversation`, API doc 4.1).

| Method | Path | Notes |
|---|---|---|
| POST | `/api/conversations` | PRIVATE `{ type, userId }`: 201 new, 200 existing pair; 400 self, 404 unknown user, 403 `MESSAGING_NOT_ALLOWED`. GROUP `{ type, name, description?, memberIds }`: 201; 400 validation, 403 `GROUP_ADD_NOT_ALLOWED` (+ `userIds`), 409 `GROUP_FULL` |
| GET | `/api/conversations` | every chat I am or was in (archived ones and groups I left too), newest activity first |
| GET | `/api/conversations/{id}` | 404 when unknown **or** I was never in it; a group I left comes back read-only (no members shown) |
| PATCH | `/api/conversations/{id}/group` | `{ name?, description?, onlyAdminsCanSend?, onlyAdminsCanEdit? }` |
| POST | `/api/conversations/{id}/members` | `{ userIds }` (1-50), admins only |
| DELETE | `/api/conversations/{id}/members/{userId}` | admins only, not yourself |
| PUT | `/api/conversations/{id}/members/{userId}/role` | `{ role: ADMIN \| MEMBER }`, admins only, not yourself |
| POST | `/api/conversations/{id}/leave` | |
| PUT / DELETE | `/api/conversations/{id}/mute` | PUT body `{ duration: EIGHT_HOURS \| ONE_WEEK \| ALWAYS }` |
| PUT / DELETE | `/api/conversations/{id}/archive` | archiving also unpins |
| PUT / DELETE | `/api/conversations/{id}/pin` | at most 3 pinned (409 `PIN_LIMIT_REACHED`); pinning also unarchives |
| GET | `/internal/conversations/{id}` | `X-Internal-Token` only: `{ id, type, name, participantIds, adminIds, onlyAdminsCanSend, members: [{ userId, joinedAt, leftAt }] }` (`name` = group name, `null` for a private chat) |
| GET | `/internal/conversations/{id}/muted-users` | `X-Internal-Token` only: `{ mutedUserIds }` = active members whose mute is still running (404 for an unknown chat). notification-service asks it before it notifies |

Group error codes: 400 `NOT_A_GROUP` (group endpoint on a private chat), 403 `NOT_A_MEMBER` (I left the group), 403 `NOT_GROUP_ADMIN`, 400 `CANNOT_CHANGE_SELF`, 409 `ALREADY_MEMBER`, 409 `GROUP_FULL`, 403 `GROUP_ADD_NOT_ALLOWED` with `data.userIds`.

Two separate security rules: `/internal/**` accepts only the internal token (a user JWT is refused), everything else accepts only a user JWT (the internal token is refused).

## How it is organised
```
controller/   ConversationController (create, list, get), GroupController (group management),
              PreferenceController (mute / archive / pin), InternalConversationController (services only)
service/      ConversationService (private chats, list, get, internal answer), GroupService (groups),
              PreferenceService (mute / archive / pin), ConversationViewService (builds what one user sees),
              LastMessageService (Kafka consumer logic)
client/       UserServiceClient (+ REST implementation with 2 s / 3 s timeouts, summaries in parts of 100)
kafka/        OutboxService + OutboxPublisher (all events -> conversation.events), GroupEvents (payloads),
              MessageEventConsumer (message.events -> lastMessageAt)
security/     SecurityConfig (two filter chains), InternalTokenFilter
util/Times    the clock, cut to microseconds
```

## Data
- `conversations`, `conversation_participants` (the **current** state: one row per user and chat, also holding that user's mute / archive / pin), `private_conversation_keys`, `group_settings` (name, description, the two admin-only switches), `membership_periods` (one row per period a user was a member: the history), `outbox_events`, `processed_events`.
- When a member leaves or is removed, their participant row stays (`active = false`, `left_at` set), so the group stays in their list read-only. Added again, the same row becomes active again and a **new** period starts.
- message-service copies the periods (internal answer + events) and only shows a user the messages created inside their periods: new members do not see older messages, removed members do not see newer ones.

## Rules worth remembering
- A pair of users has exactly one private conversation. The table `private_conversation_keys` stores the smaller user id as `user_low_id`, so (A,B) and (B,A) are the same row, and a unique key rejects a second one even when two requests arrive at the same moment (the loser returns the winner's conversation).
- The calls to user-service happen **before** the database transaction opens; no connection is held while waiting for another service. Group changes ask user-service who may be added (`/internal/users/group-add-check`: blocks + the `groupAdd` privacy setting) and the display names for the events, then change the data.
- **Every group change locks the conversation row first** (`SELECT ... FOR UPDATE`) and reads the members again inside the lock, so two admins working on the same group at the same moment run one after the other (one add, size limit, last admin all stay correct).
- When the last admin leaves, the member who joined earliest (tie: smaller user id) becomes admin; the `GroupRoleChangedEvent` (actorId null) is queued after the `GroupMemberRemovedEvent`.
- All times of one change come from one `Times.now()` (cut to microseconds), so the database rows, the internal answer and the events carry identical values. The outbox gives every event a strictly increasing `created_at`, so two events of one change are published in order.
- Events (transactional outbox, topic `conversation.events`, key = conversation id): `ConversationCreatedEvent`, `GroupMembersAddedEvent`, `GroupMemberRemovedEvent`, `GroupRoleChangedEvent`, `GroupUpdatedEvent`. A change that changes nothing (same name, same role) publishes nothing. Mute / archive / pin are personal and publish nothing.
- The consumer reads `MessageCreatedEvent` from `message.events` (group `conversation-service`) and moves `last_message_at` forward, never backwards. The same event twice does nothing (`processed_events`). Failing events are retried 3 times (1 s, 5 s, 25 s) and then copied to `message.events.dlt`; broken events go straight there.
- Tests write times with the database clock (`UTC_TIMESTAMP`) because the app stores UTC and the JVM zone is not UTC.

## Environment variables
Defaults in `application.yml` work for IDE mode (infra from `infra/docker-compose.infra.yml`); the `prod` profile has no defaults.

| Variable / property | Meaning |
|---|---|
| `CONVERSATION_DB_URL`, `CONVERSATION_DB_USERNAME`, `CONVERSATION_DB_PASSWORD` | MySQL |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka (`kafka:29092` inside Docker) |
| `JWT_SECRET` | at least 32 characters, same value in every service and the gateway |
| `INTERNAL_SERVICE_TOKEN` | at least 24 characters, same value in every service |
| `USER_SERVICE_URL` | e.g. `http://user-service:8202` |
| `SWAGGER_ENABLED` | `true` / `false` |
| `chatapp.groups.max-members` | largest group, including the creator (default 256) |
