# TESTING GUIDE

## 1. Kinds of tests and where they live

| Kind | Tools | Needs Docker? | Example |
|---|---|---|---|
| Unit (one class, no Spring) | JUnit 5, Mockito, AssertJ | no | `TokenServiceTest`, `ContactServiceTest` |
| Web layer (controller + JSON + validation, no database) | `@WebMvcTest`, MockMvc | no | `PingControllerTest`, `UserControllerTest` |
| Integration (whole app, real infrastructure) | `@SpringBootTest`, Testcontainers | **yes** | `LoginAndTokensTest`, `UserEventConsumerTest` |
| Gateway | `@SpringBootTest` + `WebTestClient`, fake backend, Redis container | yes | `JwtAuthenticationTest`, `RateLimitTest` |
| Frontend | Vitest + React Testing Library (API mocked) | no | `ProtectedRoute.test.tsx` |
| WebSocket / STOMP | real STOMP client (`TestStompClient`) against the app on a random port | yes | `WebSocketTest` |
| End to end against the running stack (API) | `infra/scripts/smoke-test.py` | stack must be running | 213 checks through the gateway |
| End to end in a real browser | Playwright with the installed Chrome (`npm run e2e` in `frontend/`) | stack must be running | chat, groups, media, presence and notifications, calls (7 tests) |
| Cross-instance live delivery | `frontend/e2e/tools/multi-instance-check.mjs` | a second message-service instance | see section 6 |

## 2. Running them
```bash
cd services/auth-service && mvn test
```
```bash
cd services/user-service && mvn test
```
```bash
cd services/api-gateway && mvn test
```
```bash
cd frontend && npm test
```
Integration tests start their own MySQL, Redis and Kafka containers (once per `mvn test`, shared by all test classes), so they never touch your local data. Docker Desktop must be running. The first run downloads the images.

```bash
cd services/conversation-service && mvn test
```
```bash
cd services/message-service && mvn test
```
Browser test (stack running; uses the Google Chrome that is already installed, nothing is downloaded):
```bash
cd frontend && npm run e2e
```
`chat.spec.ts` registers two users through the API, logs both in through the real login page in two separate browser contexts, and checks: search and add contact, start a conversation, send, the message appears for the other user without a reload with an unread badge, delivered then read ticks, typing indicator (soft check), reply, and the history after a reload. `groups.spec.ts` (Phase 4) registers three users: Alice creates a group in the UI, Bob sees it appear live with the "created group" note and Alice's name above her message, Alice removes Carol (Carol keeps the earlier message but not the next one, checked through the API), and after Alice turns on "Only admins can send messages" Bob's composer is replaced by the banner. Run one file with `npx playwright test e2e/groups.spec.ts`. On failure it keeps a screenshot and a trace in `frontend/test-results/` (`npx playwright show-trace <trace.zip>`).

Smoke test against the full stack (start it first, see `DOCKER_SETUP.md`):
```bash
python infra/scripts/smoke-test.py
```
It registers three random users, reads codes from MailHog and checks over 100 behaviours through the gateway (verification, login, profile created from Kafka, contacts, blocking, privacy, refresh rotation, logout revocation, password reset, rate limit). Exit code 0 means everything passed.

## 2b. Phase 9 checks (security, load, backup)
Start the full stack first (`DOCKER_SETUP.md`). Each of these is also run by CI (`.github/workflows/ci.yml`) except the load test.
```bash
python infra/scripts/security-check.py
```
Attacks the stack from outside like a stranger would: every protected route without a token, forged / expired / wrong-issuer tokens, internal and management endpoints, headers and CORS, hostile input, account enumeration, IDOR with two extra users, WebSocket without or with a bad token, logout. It must say `103/103 checks passed`. A failing line names the problem; nothing in it changes data except creating three throw-away users.
```bash
python infra/load-test/prepare-users.py --pairs 50
```
```bash
PAIRS=25 BROWSERS=25 bash infra/load-test/run.sh load
```
Creates verified users in pairs (access tokens last 15 minutes, so run the test right away) and runs the k6 scenario in a container. Profiles: `baseline` (idle system), `load`, `stress` (twice the users; add `SEND_MS=300` for a message every 0.3-0.6 s per sender). Needs one prepared pair per virtual user; the script tells you how many. More in `infra/load-test/README.md`. Never run it against production.
```bash
bash infra/backup/backup.sh && bash infra/backup/verify-backup.sh
```
Backs up and then proves the backup can be restored (into scratch databases, the live data is untouched). It prints how long the restore took.
```bash
cd services/api-gateway && mvn test -Dtest=ConcurrencyTest
```
The gateway must serve 60 requests at the same time (it used to answer 503 after 25).

## 3. Conventions
- Test the behaviour a user or another service sees (status code, error code, what changed), not private methods.
- Every business rule that can be broken needs a test for the broken case too (wrong password, expired code, other user's id, duplicate request).
- Integration test classes extend the service's `AbstractIntegrationTest`, which starts the containers and gives helpers (`register()`, `registerVerified()`, `login()`).
- Email is replaced by `FakeEmailService` in tests, which remembers the last code or link per address.
- Never put secrets in test files. Tests use throw-away values from `src/test/resources/application-test.properties`.

## 4. Gotchas we hit
- **Time zones.** The apps store UTC but the JVM on a developer machine may not be UTC. When a test needs to change a time column (expire a code, unlock an account), use the database clock: `UTC_TIMESTAMP(6) + INTERVAL ? SECOND`, never `java.sql.Timestamp`. See `setTimeFromNow` in `AbstractIntegrationTest`.
- **JWT clock skew.** Spring accepts tokens up to 60 seconds past their expiry. An "expired token" test must use a token that expired several minutes ago.
- **Kafka consumers in tests.** Wait with a deadline (poll for up to 20-30 s) instead of `Thread.sleep`.
- **Shared containers.** All test classes share one database, so tests create uniquely named users (random suffix) and never assume an empty table.
- **Web slice tests** only load what a controller needs. Security rules are verified in the integration tests (`SecurityRulesTest`), not in the slice.

## 5. Critical scenarios and where they are covered (from the requirement list)
| # | Scenario | Covered by |
|---|---|---|
| 1 | User A sends a message to User B | `SendMessageTest`, smoke test, Playwright |
| 2 | User B receives it immediately | `WebSocketTest.recipientAndSenderDevicesReceiveANewMessageLive`, Playwright |
| 3 + 4 | B is offline, then receives pending messages after reconnecting | `SummariesAndSyncTest.offlineRecipientCatchesUpWithSync`, smoke test, frontend sync-on-reconnect tests |
| 5 | Duplicate submission does not create duplicate records | `SendMessageTest` (same key, 8 parallel requests with the same key -> one row), `WebSocketTest` (duplicate over the socket) |
| 6 | Sent, delivered, read update correctly | `ReceiptsTest` (nothing automatic, forward only, per device, privacy) |
| 7 | Unauthorized users cannot access private conversations | `SendMessageTest`, `HistoryTest`, `ReceiptsTest`, `WebSocketTest` (subscribe, send), smoke test |
| 8 | Group delivery (only to members, every device, live) | `GroupMessagingTest` (message-service), `GroupApiFlowTest` (conversation-service), smoke test, Playwright `groups.spec.ts` |
| 9 | Removed members cannot see later messages (but keep earlier ones; re-added members do not see the gap) | `GroupMessagingTest` (history, sync, summaries, send, read, delivered), `GroupApiFlowTest` (periods), smoke test, Playwright `groups.spec.ts` |
| 10 | Media uploads are validated | `FileTypeDetectorTest`, `MediaRulesTest`, `ImageInspectorTest`, `UploadFlowTest` (media-service, real RustFS), smoke test (exe renamed .png, HTML as text, oversize, empty), Playwright `media.spec.ts` |
| 10b | Only people who may see a message can get its file | `AttachmentTest` (message-service, removed-member rule), `AttachAndLinkTest` and `OwnerAccessTest` (media-service), smoke test (stranger 404) |
| 11 | Multiple devices keep correct online status | presence-service `PresenceServiceTest` / integration tests (second device, one offline, TTL expiry), smoke test ("one device goes offline: still online"), live check: a silent device went offline after 60 s |
| 11b | Presence and notifications respect privacy, mute and preferences | `PresenceVisibilityMatrixTest` (user-service), notification-service integration tests, smoke test, Playwright `presence-notifications.spec.ts` |
| 12 | WebSocket reconnect does not create duplicate subscriptions | frontend `websocketManager` tests |
| 13 | Kafka consumer retries do not duplicate business operations | `UserEventConsumerTest`, `MessageEventConsumerTest`, `GroupEventConsumerTest` (same event twice, bad payload to the dead-letter topic) |
| 14 | Concurrent sending preserves ordering | `SendMessageTest.concurrentSendersKeepAConsistentOrderAndLoseNothing` |
| 15 | Users cannot bypass blocked-user or privacy restrictions | `SendMessageTest` (both directions), `ConversationApiFlowTest`, `UserApiFlowTest`, group-add privacy and blocks in `InternalApiTest` (user-service) and `GroupApiFlowTest`, smoke test |
| 16 | Calls: state, busy, devices, time-outs, missed calls (Phase 7) | call-signaling-service `CallTest` / `CallRulesTest` (state machine), `CallFlowTest` (every endpoint and error code, signal relay to the right device), `CallConcurrencyTest` (8 calls to one person -> 1 rings; two people calling each other; accept racing cancel), `CallTimeoutTest` (ring and ping time-outs release the busy slots), `CallEventsTest` (`CallEndedEvent` payloads), `TurnCredentialServiceTest`; notification-service `CallNotificationTest`; smoke test (34 call checks incl. the real 30 s ring timeout); Playwright `calls.spec.ts`: a real video call between two Chrome windows (both reach `connected`), the same call **forced through Coturn** (`iceTransportPolicy: relay`), a declined call and a missed call in the bell; `calls-media-flow.spec.ts` reads the browsers' WebRTC statistics and proves audio AND video bytes really arrive, direct and through the relay |
| 17 | Status / stories: audience, privacy, blocks, viewers, 24 h expiry, file clean-up (Phase 8) | status-service `VisibilityRulesTest` (the three modes, blocks), `VisibilityAndFeedTest` (feed, order, same 404 for expired / not allowed), `ViewersTest` (read-receipt rules both sides), `PostStatusTest` (idempotency, 30 limit, attachment errors), `LifecycleTest` + `ShortLifetimeTest` (delete, expiry, retry of a failed file release); user-service `InternalApiTest` (contact-owners, relation); media-service release tests (only the exact reference releases a file); smoke test (status sections); Playwright `status.spec.ts`: Alice posts text and a photo, Bob watches them (picture really loads), Alice sees Bob as viewer, "only share with nobody" hides her at once, deleting removes them for Bob |
| 18 | Security: auth bypass on every route, forged / expired tokens, IDOR, WebSocket auth, headers, CORS, hostile input, enumeration (Phase 9) | `python infra/scripts/security-check.py` (103 checks, also against the HTTPS production setup), gateway `JwtAuthenticationTest`, Trivy + `npm audit`, the per-service privacy tests |
| 19 | Load and capacity (Phase 9) | `infra/load-test/` (k6): baseline, load, stress; gateway `ConcurrencyTest` (60 parallel requests) |
| 20 | Backup can really be restored (Phase 9) | `bash infra/backup/verify-backup.sh` restores every dump into scratch databases and compares row counts; the disaster drill is in DEPLOYMENT_GUIDE.md section 6 |
| 21 | Uploads are scanned for malware (Phase 9) | media-service `ClamAvVirusScannerTest` (the clamd protocol against a fake server: clean, infected, scanner error, scanner down), and a live check with a real ClamAV and the EICAR test file |

### Media specifics
- `cd services/media-service && mvn test` starts MySQL and a **real RustFS** container (the S3 stand-in), so uploads, signed links, thumbnails and cleanup are tested against a real object store. Test files (JPEG, PNG, MP4, PDF, ...) are built in the tests from a few bytes; nothing large is committed.
- The browser test `e2e/media.spec.ts` uses Chrome's fake microphone (`--use-fake-device-for-media-stream`) so a voice note is really recorded, uploaded and played.
- `e2e/calls.spec.ts` uses Chrome's fake camera and microphone too. Its TURN-only part sets `localStorage['chatapp.iceTransportPolicy'] = 'relay'`, so the browsers may only use relay candidates: reaching `connected` proves Coturn and the short-lived credentials work. Coturn's log shows the allocations (`docker logs chat-coturn`).
- Big uploads (about 100 MB) are not part of the automated tests (too slow); try them by hand with `python` and `smoke-test.py`'s `upload()` helper, or the one-off check described in IMPLEMENTATION_CHECKLIST.md (Phase 5).
- Time again: `UTC_TIMESTAMP(6)` in SQL when tests age rows (orphan cleanup), never `java.sql.Timestamp`.

## 6. Checking two message-service instances by hand
The automated tests prove the Redis fan-out inside one instance (`WebSocketTest.eventFromAnotherInstanceReachesALocalSocketThroughRedis`). To see it with two real instances:
```bash
docker run -d --name chat-message-service-2 --network chat-net -p 8214:8204 --env-file infra/.env -e SPRING_PROFILES_ACTIVE=prod -e "MESSAGE_DB_URL=jdbc:mysql://mysql:3306/message_db?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC" -e MESSAGE_DB_USERNAME=message_user -e REDIS_HOST=redis -e REDIS_PORT=6379 -e KAFKA_BOOTSTRAP_SERVERS=kafka:29092 -e CONVERSATION_SERVICE_URL=http://conversation-service:8203 -e USER_SERVICE_URL=http://user-service:8202 -e CORS_ALLOWED_ORIGINS=http://localhost:5280 chat-app-message-service
```
```bash
cd frontend && node e2e/tools/multi-instance-check.mjs
```
Bob's WebSocket connects to instance 2, Alice sends through the gateway (instance 1), and the script passes only if Bob still receives the message. Remove the extra container afterwards (`docker rm -f chat-message-service-2`). Spreading browser traffic over several instances needs a load balancer in front of the message-service (a deployment topic, Phase 9).
