# user-service

Profiles, user search, contacts, blocking and privacy settings.
The profile is created automatically when auth-service publishes a `UserRegisteredEvent` on Kafka.

- Port: 8202 | Database: `user_db` (user `user_user`)
- API: see `API_DOCUMENTATION.md` section 3 (`/api/users/**`, `/api/contacts/**`). Every endpoint needs a JWT; the caller is always the token's `sub`.
- Swagger UI: http://localhost:8202/swagger-ui.html (dev) - use "Authorize" with an access token.

## Internal API (for other services)
`/internal/**` is only for other services: never routed by the gateway, hidden from Swagger, and protected by the shared header `X-Internal-Token` (value of `INTERNAL_SERVICE_TOKEN`) instead of a user JWT. A user JWT does **not** work here, and the internal token does **not** work on `/api/**` (two separate security chains: `InternalSecurityConfig` and `SecurityConfig`). A missing or wrong token answers 401 in the standard JSON format.

| Endpoint | Answer (`data`) |
|---|---|
| `GET /internal/blocks?a={uuid}&b={uuid}` | `{ blocked }` - true when **either** user blocked the other |
| `GET /internal/users/{id}/privacy` | `Privacy` (404 if the user does not exist) |
| `POST /internal/users/summaries` body `{ viewerId, userIds[] }` (max 100, else 400) | `[PublicProfile]` as `viewerId` may see them: unknown ids and users who blocked the viewer are left out, privacy applied, same order as the request |
| `POST /internal/users/presence-visibility` body `{ viewerId, targetId }` | `{ visible }`: may the viewer see the target's online status / last seen? Yourself: always. Not visible when the target is unknown or suspended, when either blocked the other, when the **viewer's own** `lastSeen` is `NOBODY` (reciprocity), or when the target's `lastSeen` is `NOBODY`, or `CONTACTS` and the **target** has not saved the viewer as a contact (the direction matters). Missing privacy rows count as `EVERYONE`. Used by presence-service (`PresenceVisibilityService`) |
| `POST /internal/users/group-add-check` body `{ actorId, userIds[] }` (1-256, else 400) | `{ notAllowed: [uuid] }`: the users the actor may NOT add to a group, in request order. Not allowed = unknown (or not active), blocked in either direction, or their `groupAdd` privacy refuses the actor (`CONTACTS`: only if the user saved the actor in their own contact list; `NOBODY`: never). One answer for all reasons on purpose. Used by conversation-service (`GroupAddPolicyService`) |
| `GET /internal/users/{viewerId}/contact-owners` | `{ ownerIds: [uuid] }`: everybody who saved `viewerId` in **their own** contact list, minus anybody blocked in either direction (one query). Used by status-service (Phase 8) to build the status feed: only these people's statuses can be visible to the viewer |
| `GET /internal/contacts/relation?ownerId={uuid}&viewerId={uuid}` | `{ contact, blocked }`: `contact` = the **owner** saved the viewer (the other direction does not count; yourself: false), `blocked` = either blocked the other. Used by status-service (Phase 8) before showing one status |

## Run (IDE mode, from this folder)
Start the infrastructure first (see `DOCKER_SETUP.md`), then:
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```
Passwords and `JWT_SECRET` are read from `infra/.env` automatically (that is why you must run from this folder).

## Build, test, Docker
```bash
mvn test
mvn package
docker build -t chat-user-service .
```
`mvn test` starts MySQL and Kafka in Docker (Testcontainers), so **Docker must be running**. The first run downloads images and takes a minute; the containers are shared by all test classes.

| Tests | What they cover |
|---|---|
| `service/*Test` | Mockito unit tests: privacy rules, search escaping, contact and block rules, event idempotency |
| `controller/*Test` | `@WebMvcTest`: 401 without/with bad/expired/forged token, 400 validation, caller id from token |
| `integration/SchemaTest` | Flyway migration + Hibernate `validate` on a real MySQL |
| `integration/UserEventConsumerTest` | real Kafka: event creates user, duplicate event ignored, broken event goes to `user.events.dlt` |
| `integration/UserApiFlowTest` | full HTTP flow with real signed JWTs: profile, search, contacts, block, privacy |
| `controller/InternalControllerTest` | internal token required/wrong, user JWT and internal token not interchangeable, validation (max 100 ids, group-add check 1-256 ids, presence-visibility needs both ids) |
| `integration/InternalApiTest` | block check both directions, privacy lookup, summaries with privacy / blocked viewer / unknown ids, group-add check (EVERYONE / CONTACTS / NOBODY, blocks both ways, unknown users) |
| `service/GroupAddPolicyServiceTest` | the group-add rules one by one (Mockito) |

## Environment variables
| Variable | Default (dev) | Notes |
|---|---|---|
| `USER_DB_URL` | `jdbc:mysql://localhost:3330/user_db?...` | required in `prod` |
| `USER_DB_USERNAME` | `user_user` | required in `prod` |
| `USER_DB_PASSWORD` | none | always required |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9094` | `kafka:29092` in Docker |
| `JWT_SECRET` | none | at least 32 characters, same value as auth-service and the gateway |
| `INTERNAL_SERVICE_TOKEN` | none | at least 24 characters, same value in every service; the service refuses to start without it |
| `SWAGGER_ENABLED` | `true` (dev), `false` (prod) | |

## How it is built
```
controller -> service -> repository -> MySQL
kafka/consumer (UserEventConsumer) -> UserRegistrationService   (one transaction, idempotent)
security: JWT check (SecurityConfig), 401/403 in the standard JSON format
```
- `ProfileVisibilityService` holds all privacy rules (everyone / contacts / nobody; blocked users see nothing private).
- Kafka: retries 3 times (1 s, 5 s, 25 s), then the event is copied to `user.events.dlt`. Broken events (bad JSON, missing fields) go to the dead-letter topic immediately.
- Profile photo upload arrives with media-service (Phase 5); `profileImageUrl` is read-only for now.
