# WEBSOCKET DESIGN

## 1. Endpoints
| Endpoint | Service | Purpose |
|---|---|---|
| `/ws` | message-service | the app's ONE socket: messages, receipts, typing, errors, notifications (Phase 6) and call events (Phase 7) |

Phase 7 did **not** add the planned `/ws/calls` socket: call events reach the browser through this socket (section 9) and the browser sends its call messages with REST.

Protocol: STOMP over a plain WebSocket (no SockJS). Browser URL: `ws(s)://<host>/ws` (same host as the app; nginx / the Vite dev proxy forwards `/ws` to the gateway, the gateway forwards to message-service).

## 2. Authentication
1. The browser opens the socket. **No token in the URL** (URLs get logged), and the gateway lets `/ws` through without a token because browsers cannot add headers to a WebSocket handshake.
2. The STOMP `CONNECT` frame carries the header `Authorization: Bearer <accessToken>`. message-service validates it exactly like the gateway does: HS256 signature with the shared `JWT_SECRET`, issuer `chat-app-auth`, expiry, and the Redis key `auth:revoked:<sid>` must not exist. Invalid -> an `ERROR` frame and the socket closes.
3. The authenticated user id (`sub`) becomes the STOMP `Principal`; `sid` (the login session id) is remembered as the **device id**.
4. **Token expiry:** a connection is only as long-lived as its access token. Every 30 seconds the server closes connections whose token has expired or whose session was revoked (close status 1008). The client then refreshes its token and reconnects (with the normal backoff). Access tokens last 15 minutes, so a socket reconnects at least that often.

**The sender is always the principal.** A `senderId` in a payload is ignored (and the DTOs do not even have one).

## 3. Destinations

Client sends (`/app/...`, JSON bodies). Anything else is rejected.
| Destination | Payload | Effect |
|---|---|---|
| `/app/chat.send` | `{ conversationId, type: "TEXT", content, idempotencyKey }` | same as `POST /api/messages`. Answer goes to `/user/queue/acks` or `/user/queue/errors` |
| `/app/chat.typing` | `{ conversationId, typing: true\|false }` | only current members; sent to `/user/queue/typing` of every current member (Phase 4; before: `/topic/conversation/{id}`); limited to 1 per second per socket (extra ones are dropped) |
| `/app/chat.delivered` | `{ messageIds: [uuid] }` | same as `POST /api/messages/delivered` |
| `/app/chat.read` | `{ conversationId, upToMessageId }` | same as `POST /api/conversations/{id}/read` |

Client subscribes (anything else is rejected):
| Destination | Receives |
|---|---|
| `/user/queue/messages` | `MESSAGE_CREATED`: every new message in my conversations, **including my own** (other devices of mine, and a confirmation for this one) |
| `/user/queue/receipts` | `RECEIPT_UPDATED`: delivery/read changes of messages I sent |
| `/user/queue/acks` | `MESSAGE_ACK`: answer to `/app/chat.send` |
| `/user/queue/errors` | `ERROR`: a rejected frame |
| `/user/queue/notifications` | `NOTIFICATION` (Phase 6): a notification was created or its count grew. Published by **notification-service** into the same Redis channel `ws.events` (same envelope as message-service uses), so the bell can update without polling |
| `/user/queue/typing` | `TYPING` of the conversations I am a current member of (Phase 4). `/topic/conversation/{id}` was removed: a topic subscription would outlive a removal from a group |
| `/user/queue/calls` | `CALL_INCOMING`, `CALL_ACCEPTED`, `CALL_ENDED`, `CALL_SIGNAL` (Phase 7). Published by **call-signaling-service** into the Redis channel `ws.events` (same envelope), see section 9 |

`/user/queue/...` is per user: the server resolves it to the authenticated principal, so nobody can subscribe to someone else's queue. Presence and calls come in later phases.

Sending to the same user from several devices: every connected device receives the event.

## 4. Event payloads (server -> client)
```json
{ "type": "MESSAGE_CREATED", "eventId": "uuid", "conversationId": "uuid", "data": { "...Message..." }, "timestamp": "2026-11-04T10:00:00.123456Z" }
```
| `type` | `data` |
|---|---|
| `MESSAGE_CREATED` | `Message` (API doc section 5) with `status: "SENT"`, `myReceipt: null`, and `attachment` (metadata of the file for media messages, otherwise `null`; the file itself comes from a signed link, see API_DOCUMENTATION.md section 5) |
| `MESSAGE_CREATED` (SYSTEM) | a SYSTEM `Message` (`status: null`) for a group change. Sent to every current member and to the member who was removed or left. Clients refresh that conversation (`GET /api/conversations/{id}`) when they receive one, because members, roles, name or settings may have changed |
| `RECEIPT_UPDATED` | `{ messageId, conversationId, userId (who acknowledged), status: "DELIVERED" \| "READ" }`. `status` is what the **sender** should now show for the whole message: in a private chat that recipient's status (a read receipt hidden by privacy arrives as `DELIVERED`); in a group the combined status of all recipients (sent only once it is at least `DELIVERED`; it may repeat). Clients must never move a status backwards |
| `MESSAGE_ACK` | `{ idempotencyKey, message: Message }` |
| `ERROR` | `{ code, message, idempotencyKey? }` (codes as in the REST API: `MESSAGING_NOT_ALLOWED`, `NOT_FOUND`, `VALIDATION`, `RATE_LIMITED`, `DEPENDENCY_UNAVAILABLE`) |
| `NOTIFICATION` | `Notification` (API_DOCUMENTATION.md 7.2) |
| `TYPING` | `{ conversationId, userId, typing }` (the typing user also receives it: clients ignore their own id). In a group several people can type at once: clients track typing per user |

## 5. Multi-instance delivery
```
Instance A (handles the request)        Redis channel `ws.events`            Instance B (user's socket)
 save message + outbox row  ---------->  {userIds | topic, destination, json} ---> local in-memory broker
 REST answer to the sender                                                          convertAndSendToUser(...)
```
- Every message-service instance subscribes to `ws.events`; each one pushes only to sockets connected to **itself**. A user who is not connected anywhere gets nothing live and catches up with `GET /api/messages/sync` when they reconnect.
- Events are published **after** the database transaction commits, so a client never receives a message that does not exist.
- Kafka is for durable asynchronous work (conversation `lastMessageAt`, later notifications). It is **not** the live path.

## 6. Heartbeat
STOMP heartbeats 10 s / 10 s. A socket that stops answering is closed by the server and the client reconnects. (Online/offline presence is Phase 6.)

## 7. Client behaviour (frontend)
- One `WebSocketManager` singleton; guards against duplicate connections and duplicate subscriptions (a registry keyed by destination; subscriptions are re-created after each reconnect).
- Reconnect with exponential backoff + jitter (1 s, 2 s, 4 s ... max 30 s). Before reconnecting it makes sure the access token is fresh.
- After every (re)connect: `GET /api/messages/sync?after=<lastCursor>` to catch up, then acknowledge anything not yet delivered, then refresh the chat list and unread counts.
- Sending: the UI shows the message immediately as `SENDING` (optimistic). It is sent with `POST /api/messages` and a client generated `idempotencyKey`, which is safe to retry. On success the temporary message is replaced by the server's; on failure it shows `FAILED` with a retry button (same key). `/app/chat.send` exists for completeness but the REST call is the reliable path (it returns the acknowledgement directly).
- Receiving: show the message, send `delivered` for it; when the conversation is open and the tab is visible also send `read`.

## 8. Authorization summary
- CONNECT: valid token, not revoked.
- SUBSCRIBE: only the five `/user/queue/...` destinations above (they always resolve to the authenticated user).
- SEND: only `/app/chat.*`; every handler re-checks that the user is a current member of the conversation in the payload (asked from conversation-service, the source of truth), applies the block rule (private chats) and `onlyAdminsCanSend` (groups).
- Frame and message size are limited (64 KB frame, 4000 characters of text).

## 9. Calls (Phase 7)
**Transport.** The browser **sends** call messages with REST (`/api/calls/...`, API_DOCUMENTATION.md 7.3) and **receives** them on `/user/queue/calls` of the chat socket. call-signaling-service publishes them to the Redis channel `ws.events` with the usual envelope `{ userIds, destination: "/queue/calls", json }`; every message-service instance pushes them to the sockets of those users that it holds (section 5). So there is one socket per tab, one WebSocket security set-up, and the proven multi-instance delivery, instead of a second socket with its own authentication, expiry checks and fan-out. Signaling messages are small and few (one offer, one answer, a few batches of ICE candidates), so REST adds no noticeable delay.

Events (same outer shape as every event: `{ type, eventId, conversationId: null, data, timestamp }`):
| `type` | to | `data` |
|---|---|---|
| `CALL_INCOMING` | the callee (all devices) | `Call` (callee's view: `direction: "INCOMING"`, status `RINGING`) |
| `CALL_ACCEPTED` | caller and callee (all devices) | `{ call: Call (the receiver's view), calleeDeviceId }`. The caller's call device now sends the OFFER; the callee's other devices (and tabs that did not press Accept) stop ringing |
| `CALL_ENDED` | caller and callee (all devices) | `Call` in its final state (`ENDED`, `MISSED`, `REJECTED`, `BUSY`) with `endReason` |
| `CALL_SIGNAL` | the other participant | `{ callId, fromUserId, toDeviceId, signalType: "OFFER" \| "ANSWER" \| "ICE", sdp (or null), candidates (or null) }`. Every device of that user receives it; only the tab that is in this call uses it |

Rules: only the two participants receive anything about a call; signals are relayed only between the two call devices (API 7.3); a lost live event is repaired by the time limits (ring timeout, ping timeout) and by `GET /api/calls/active` after a reconnect. Delivery through Redis keeps the order of one publisher, but two requests can reach two instances: clients must accept ICE candidates before the offer/answer (keep them and add them later).
