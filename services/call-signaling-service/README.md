# call-signaling-service

One-to-one **audio and video calls** (Phase 7). Audio and video flow directly between the two browsers (WebRTC); this
service only keeps the **state of each call**, decides who is **busy**, **relays the connection set-up messages**
(offer, answer, ICE candidates) between the two call devices, hands out **short-lived TURN passwords** for Coturn, and
keeps the **call history**.

- Port: 8208 - database `call_db` (user `call_user`) - Redis (publish only) - Kafka (publishes `call.events`)
- Calls user-service (`/internal/blocks`, `/internal/users/summaries`) with the shared `X-Internal-Token`
- Build and test: `mvn test` (needs Docker: Testcontainers starts MySQL, Redis and Kafka)
- Run (IDE mode, from this folder): `mvn spring-boot:run -Dspring-boot.run.profiles=dev` (infrastructure from
  `infra/docker-compose.infra.yml`, which includes Coturn)
- Swagger UI (dev): http://localhost:8208/swagger-ui.html, or through the gateway at `/service-docs/call`

## Endpoints (all need a bearer token; user = token `sub`, device = token `sid`)

| Method | Path | What it does |
|---|---|---|
| POST | /api/calls `{ calleeId, type: AUDIO \| VIDEO }` | 201 `{ call, iceServers }`: RINGING, or BUSY when the callee is in another call. 400 yourself, 404 unknown, 403 `CALL_NOT_ALLOWED` (blocked), 409 `ALREADY_IN_CALL` |
| GET | /api/calls/active | my RINGING or ACCEPTED call, or `null` |
| GET | /api/calls/history?page=0&size=20 | my calls, newest first (size 1-50) |
| GET | /api/calls/{id} | one of my calls (404 otherwise) |
| POST | /api/calls/{id}/accept | callee only; this device becomes the call device; `{ call, iceServers }` |
| POST | /api/calls/{id}/reject | callee only, while ringing |
| POST | /api/calls/{id}/end | hang up; while ringing: cancel (caller) / decline (callee); safe to repeat |
| POST | /api/calls/{id}/signal `{ type: OFFER \| ANSWER \| ICE, sdp?, candidates? }` | relayed to the other call device, never stored |
| POST | /api/calls/{id}/ping | "my side is alive", every 10 s during the call |

Full contract: `API_DOCUMENTATION.md` 7.3. Live events (`CALL_INCOMING`, `CALL_ACCEPTED`, `CALL_ENDED`, `CALL_SIGNAL`):
`WEBSOCKET_DESIGN.md` section 9.

## How it works

- **State machine** (`entity/Call.java`): RINGING -> ACCEPTED -> ENDED (HANGUP / CONNECTION_LOST); RINGING ->
  REJECTED (DECLINED) or MISSED (NO_ANSWER / CANCELLED); BUSY is final at once. Final states never change.
- **Every change** locks the call row (`SELECT ... FOR UPDATE`) inside one transaction, so "accept" and "cancel" at the
  same moment have exactly one winner.
- **Busy** = a row per user in `call_slots` (primary key = user id) taken with `INSERT IGNORE` in the transaction that
  creates the call, always in the same order (by id) so two calls never wait for each other in a circle. Both rows are
  deleted when the call reaches a final state.
- **Devices:** the call rings on all of the callee's devices; the device that accepts becomes the callee's call device;
  signals and pings are accepted only from the two call devices (`NOT_CALL_DEVICE` otherwise).
- **Live events** are published to the Redis channel `ws.events` (envelope `{ userIds, destination: "/queue/calls", json }`)
  after the transaction committed; message-service pushes them to the browser's existing WebSocket. Each participant
  gets their own view of the call (direction, other person).
- **Time-outs** (`service/CallTimeoutJob.java`, every second, safe on several instances with `FOR UPDATE SKIP LOCKED`):
  ringing for 30 s -> MISSED / NO_ANSWER; an accepted call where one side has not pinged for 30 s -> ENDED /
  CONNECTION_LOST. So a crashed browser never leaves anybody busy.
- **Kafka:** every final call is queued as `CallEndedEvent` in the outbox (same transaction) and published to
  `call.events` (key = call id). notification-service turns MISSED / BUSY into a missed-call notification.
- **TURN passwords** (`service/TurnCredentialService.java`): username `<expiry epoch seconds>:<userId>`, password
  `base64(HMAC-SHA1(TURN_SECRET, username))`, valid 8 hours. Coturn (`--use-auth-secret`) checks them with the same
  secret, so no password is stored anywhere.
- user-service being down: starting a call answers 503 (blocks must be checked), but accept / end / signal keep
  working (the other person's name then shows as "Unknown user").

## Configuration (environment variables)

`CALL_DB_URL`, `CALL_DB_USERNAME`, `CALL_DB_PASSWORD`, `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`,
`KAFKA_BOOTSTRAP_SERVERS`, `USER_SERVICE_URL`, `JWT_SECRET` (same as auth-service), `INTERNAL_SERVICE_TOKEN`,
`TURN_SECRET` (same as Coturn's `--static-auth-secret`, at least 16 characters; the service refuses to start otherwise),
`TURN_PUBLIC_HOST` (the address **browsers** use to reach Coturn, `localhost` locally), `TURN_PORT` (3478),
`SWAGGER_ENABLED`. Time limits: `chatapp.calls.ring-timeout-seconds` (30), `ping-timeout-seconds` (30),
`timeout-check-ms` (1000); `chatapp.turn.credential-ttl-seconds` (28800).

## Tests

- `entity/CallTest`: the state machine rules, without Spring.
- `service/TurnCredentialServiceTest`: the TURN password against a value computed independently with openssl.
- `controller/CallControllerTest`: request validation (start body, signal body rules and limits), token handling.
- Integration (real MySQL, Redis, Kafka; fake user-service):
  `CallFlowTest` (ring, accept, signal routing to the right device, hang up, active, history, access),
  `CallRulesTest` (self / unknown / blocked, busy, already in a call, only the callee accepts, several devices,
  decline, cancel, repeated end, signal and ping rules, bad signal bodies, user-service down),
  `CallConcurrencyTest` (8 callers at once -> one rings; two people calling each other at once; accept racing the
  caller's hang-up), `CallTimeoutTest` (ring and ping time-outs), `CallEventsTest` (`CallEndedEvent` payloads).
