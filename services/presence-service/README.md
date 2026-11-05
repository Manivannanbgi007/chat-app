# presence-service

Who is online, and when was somebody last seen. **Redis only** (no MySQL, no Kafka). API: `API_DOCUMENTATION.md` section 7.1. Design: `ARCHITECTURE.md` 3e.

- Port: 8207
- Uses: Redis (all data), user-service over REST (privacy: who may see whom)
- Build and test: `mvn test` (needs Docker: Testcontainers starts a Redis with a password; user-service is replaced by a small fake)
- Run (IDE mode, from this folder): `mvn spring-boot:run -Dspring-boot.run.profiles=dev`
- Docker image: `docker build -t chat-presence-service .`
- Swagger UI: http://localhost:8207/swagger-ui.html

## How it works
The app sends `POST /api/presence/heartbeat` every 20 seconds. The token tells us the user (`sub`) and the device (`sid`, the login session id).

```
presence:devices:{userId}      sorted set: member = device id, score = when that device stops counting as online
presence:lastactive:{userId}   the time of the last heartbeat / offline call (kept 30 days)
```
- A device is alive for **60 seconds** after its last heartbeat (`PRESENCE_TTL_SECONDS`). A crashed tab or a lost connection simply expires.
- A user is **online** while at least one device is alive. One device going offline (`POST /api/presence/offline`, called on logout / page close) does not make the user offline while another device is alive.
- **Last seen** = the time of the last heartbeat or offline call. It is 30 days at most.
- A heartbeat is one atomic Lua script (drop expired devices, refresh this one, refresh the key TTL, remember the time), so parallel heartbeats cannot corrupt anything.

## Privacy (never decided here)
`GET /api/presence/{userId}` asks user-service (`POST /internal/users/presence-visibility`). You always see yourself without asking. When the answer is "not visible" the response has `visible: false` and `online` / `lastSeenAt` are `null`. When user-service cannot answer we answer **503** (show nothing) instead of guessing. Redis problems are 503 too.

## Internal endpoint (other services only, header `X-Internal-Token`)
`GET /internal/presence/online?userIds=a,b,c` (1 to 200 ids) -> `{ onlineUserIds }`. No privacy applied: it is for server-side decisions (notification-service asks it before sending a push). A user JWT does not work there, and the internal token does not work on `/api/**`.

## Environment variables
Defaults in `application.yml` work in IDE mode (infra from `infra/docker-compose.infra.yml`); the `prod` profile has no defaults.

| Variable | Meaning |
|---|---|
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | Redis |
| `USER_SERVICE_URL` | where user-service lives (default `http://localhost:8202`) |
| `JWT_SECRET` | at least 32 characters, same value in every service |
| `INTERNAL_SERVICE_TOKEN` | at least 24 characters, same value in every service |
| `PRESENCE_TTL_SECONDS` | how long a device stays online after a heartbeat (default 60) |
| `SWAGGER_ENABLED` | `true` / `false` |

## Code map
```
controller/   PresenceController (/api/presence), InternalPresenceController (/internal/presence)
service/      PresenceService: privacy decision + Redis errors become 503
repository/   PresenceRepository: the only class that talks to Redis (Lua scripts, pipelined lookup)
client/       UserClient / RestUserClient: the privacy question to user-service
```

## Tests
| Test | What it proves |
|---|---|
| `PresenceFlowTest` (Redis) | heartbeat -> online, second device, one device offline, never seen, idempotent heartbeats, 100 parallel devices, key TTLs, privacy hidden -> nulls, self always visible (even if user-service is down), 503 when it is down, internal lookup (mixed ids, 200 ok / 201 refused, token rules) |
| `PresenceExpiryTest` (Redis, TTL 2 s) | a device without heartbeats expires by itself, last seen = last heartbeat, devices expire one by one, expired members are cleaned out of the set |
| `PresenceControllerTest` | status codes, token rules for both security chains, 400 for bad ids, error format |
| `PresenceServiceTest`, `PresenceKeysAndSettingsTest` | the rules with mocks, key names, setting checks |

## Known limits
- Presence can lag up to 60 s after a crash (no heartbeat to notice it).
- The privacy question is asked on every read (a cache is possible if load tests show it matters).
- There is no live push of status changes: the open chat polls this endpoint (every 10 s).
