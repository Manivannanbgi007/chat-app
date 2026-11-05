# KAFKA EVENT DESIGN

JSON events first. Avro + Schema Registry only when cross-service schema governance is actually needed.

## 1. Envelope (identical in every service, copied not shared)
```json
{
  "eventId": "uuid",
  "eventType": "MessageCreatedEvent",
  "aggregateId": "uuid",
  "occurredAt": "2026-11-04T10:00:00.123Z",
  "schemaVersion": 1,
  "correlationId": "uuid",
  "payload": { }
}
```
Rules: add fields only (backwards compatible); bump `schemaVersion` for breaking changes; never put message text in events that do not need it (notifications get a preview flag, not content, unless the user preference allows).

## 2. Topics

| Topic | Key | Events | Producer | Consumers (group id) |
|---|---|---|---|---|
| `user.events` | userId | UserRegistered, UserProfileUpdated, UserBlocked, UserUnblocked | auth, user | user (`user-service`), message (`message-service-cache`), notification |
| `conversation.events` | conversationId | ConversationCreated, GroupMembersAdded, GroupMemberRemoved, GroupRoleChanged, GroupUpdated | conversation | message (`message-service`: membership copy + SYSTEM messages), notification (later) |
| `message.events` | **conversationId** | MessageCreated, MessageDelivered, MessageRead, MessageDeleted | message | conversation (`conversation-service`), notification |
| `media.events` | mediaId | MediaUploaded | media | message (optional) |
| `call.events` | callId | CallEnded (Phase 7; CallInitiated is not published: nobody needs it) | call-signaling | notification (`notification-service`: missed-call notifications) |
| `notification.requests` | userId | NotificationRequested | any | notification |
| `<topic>.retry` / `<topic>.dlt` | same key | failed events | consumer error handler | ops tooling |

Each consumer service has its **own consumer group**, so every service gets every event.
Partitions: 6 locally (enough to show parallelism). Using `conversationId` as key keeps order inside a conversation.

## 3. Key payloads
- `UserRegisteredEvent`: userId, username, email, phone, displayName.
- `ConversationCreatedEvent` (topic `conversation.events`, key conversationId): conversationId, type, participantIds[], createdAt.
- `MessageCreatedEvent` (topic `message.events`, key conversationId): messageId, conversationId, senderId, type, createdAt, recipientIds[] (resolved at send time). **No message text**: private content stays in message-service; a consumer that needs a preview (notifications, Phase 6) must decide that explicitly.
- `MessageDeliveredEvent` / `MessageReadEvent` (topic `message.events`, key conversationId): messageId, conversationId, senderId, recipientId, deviceId, at.
- Phase 3 consumers: conversation-service reads `MessageCreatedEvent` to update the conversation's `lastMessageAt`.
- Phase 4, topic `conversation.events`, key conversationId, all times UTC with microseconds (truncated, so they equal the database values):
  - `ConversationCreatedEvent` gains (added fields only, schemaVersion stays 1): `createdBy`, `groupName` (GROUP only, else null), `createdByName` (GROUP only). Every participant joined at `createdAt`.
  - `GroupMembersAddedEvent`: conversationId, actorId, actorName, members: [{ userId, name }], joinedAt.
  - `GroupMemberRemovedEvent`: conversationId, actorId, actorName, userId, userName, reason (`REMOVED` | `LEFT`), leftAt. For LEFT the actor is the user.
  - `GroupRoleChangedEvent`: conversationId, actorId (null = automatic, the last admin left), actorName, userId, userName, role (`ADMIN` | `MEMBER`), at.
  - `GroupUpdatedEvent`: conversationId, actorId, actorName, at, name (the new name, or null if unchanged), descriptionChanged (boolean), onlyAdminsCanSend (Boolean, null if unchanged), onlyAdminsCanEdit (Boolean, null if unchanged).
  - Names are display names at the time of the change (null if user-service did not return one). They are only used to write SYSTEM messages; the events never contain message text.
- Phase 6 consumer: **notification-service** (group `notification-service`) reads `message.events` (`MessageCreatedEvent` of type other than SYSTEM -> new-message notifications for offline, unmuted recipients; `MessageReadEvent` -> marks that chat's notification read; other message events ignored) and `conversation.events` (`ConversationCreatedEvent` of a GROUP, `GroupMembersAddedEvent`, `GroupMemberRemovedEvent` with reason REMOVED, `GroupRoleChangedEvent` with role ADMIN -> group notifications; others ignored). Each event in ONE transaction with `processed_events`; retries 1 s / 5 s / 25 s then `message.events.dlt` / `conversation.events.dlt`; malformed events go straight to the dead-letter topic.
- Phase 4 consumer: message-service (group `message-service`) reads `conversation.events`. For each event, in ONE transaction: record the eventId in `processed_events`, update its membership copy (`conversation_members` periods), write the SYSTEM message(s) with `createdAt` = the time of the change and idempotency key `sys-<eventId>-<n>`, and queue a `MessageCreatedEvent` (type SYSTEM) in its outbox. After the commit it pushes `MESSAGE_CREATED` live to the current members (and to the member who was removed or left). PRIVATE `ConversationCreatedEvent`s only fill the membership copy. Retries 1 s / 5 s / 25 s, then `conversation.events.dlt`.
- Known limit: a SYSTEM message gets the time of the change, but it is written when the event is consumed (normally under a second later). If Kafka is down for longer than the 10-second sync look-back, a client that synced in between does not get that SYSTEM message through `sync` (it still appears in the history and the chat list).
- `CallEndedEvent` (Phase 7, topic `call.events`, key callId, written through call-signaling-service's outbox when a call reaches a final state): callId, callerId, calleeId, type (`AUDIO` | `VIDEO`), outcome (`ANSWERED` | `MISSED` | `REJECTED` | `BUSY`), endReason (as in the API: `HANGUP`, `CONNECTION_LOST`, `NO_ANSWER`, `CANCELLED`, `DECLINED`, `BUSY`), createdAt, answeredAt (or null), endedAt, durationSeconds. ANSWERED = the call was accepted (status ENDED).
- Phase 7 consumer: **notification-service** (group `notification-service`) reads `call.events`: outcome `MISSED` or `BUSY` -> a `MISSED_CALL` notification for the callee if their `callsEnabled` preference allows it (online or offline; push only when offline, as for every notification); `ANSWERED` / `REJECTED` -> nothing. One transaction with `processed_events`; retries 1 s / 5 s / 25 s, then `call.events.dlt`.

## 4. Producing: transactional outbox
1. Business transaction writes the entity **and** an `outbox_events` row.
2. `OutboxPublisher` (`@Scheduled`, ~500 ms) selects unpublished rows (oldest first, batch of 100, `FOR UPDATE SKIP LOCKED`), sends to Kafka, marks `published`.
3. At-least-once delivery: duplicates are possible, so all consumers are idempotent.
4. Old published rows are deleted by a daily cleanup.

## 5. Consuming: idempotent + retry + DLT
- At the start of processing, insert `eventId` into `processed_events` (unique) in the same DB transaction as the business change. A duplicate key means "already done" -> skip.
- Manual commit after success.
- Spring Kafka `DefaultErrorHandler` with exponential backoff (3 tries: 1s, 5s, 25s) -> then publish to `<topic>.dlt`.
- Non-retryable errors (bad payload) go straight to DLT.
- Note: Spring Kafka resets the retry count if the exception class changes between attempts, so throw one consistent exception type per failure reason.

## 6. Monitoring
- Kafka UI for topics/groups locally.
- Micrometer Kafka consumer metrics (lag) -> Prometheus (Phase 9).
- Alert on DLT growth and outbox backlog (`count(published=false)`).

## 7. What Kafka is NOT used for
- Live delivery to browsers (WebSocket + Redis fan-out does that).
- WebRTC audio/video.
- Request/response calls between services.

> Note (Phase 5): `MediaUploadedEvent` is **not published yet**: no service consumes it. The notification service (Phase 6) or a future virus-scan worker can add it through the outbox.
