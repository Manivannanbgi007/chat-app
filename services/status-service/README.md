# status-service

**Status / stories** (Phase 8): short updates (text on a coloured background, a photo or a video with a caption) that
disappear **24 hours** after they were posted. This service keeps the statuses, who viewed them, and each person's
status privacy. It sends no Kafka events and uses no Redis.

- Port: 8209 - database `status_db` (user `status_user`) - MySQL only
- Calls (REST, header `X-Internal-Token`): **user-service** (who has me as a contact, blocks, read-receipt settings, names)
  and **media-service** (attach, signed links, release a file)
- Build and test: `mvn test` (needs Docker: Testcontainers starts MySQL; user-service and media-service are small fakes)
- Run (IDE mode, from this folder): `mvn spring-boot:run -Dspring-boot.run.profiles=dev` (infrastructure from
  `infra/docker-compose.infra.yml`, plus user-service and media-service)
- Swagger UI (dev): http://localhost:8209/swagger-ui.html, or through the gateway at `/service-docs/status`
- An existing MySQL volume needs `status_db` created once by hand: see DOCKER_SETUP.md

## Endpoints (all need a bearer token; user = token `sub`)

| Method | Path | What it does |
|---|---|---|
| POST | /api/statuses | TEXT `{ type, text, backgroundColor, idempotencyKey }` or IMAGE / VIDEO `{ type, mediaId, caption?, idempotencyKey }`. 201, or 200 with the same status for a repeated key |
| GET | /api/statuses/mine | my active statuses, oldest first, with `viewCount` |
| GET | /api/statuses/feed | people whose statuses I may see: groups with something new first |
| POST | /api/statuses/{id}/view | "I saw it" (safe to repeat) |
| GET | /api/statuses/{id}/viewers | who viewed my status (own statuses only) |
| GET | /api/statuses/{id}/media-url?variant=original\|thumbnail | signed link, valid 5 minutes |
| DELETE | /api/statuses/{id} | delete my status (and its file) |
| GET / PUT | /api/statuses/privacy | `CONTACTS`, `CONTACTS_EXCEPT` or `ONLY_SHARE_WITH` + `userIds` |

Full contract and error codes: `API_DOCUMENTATION.md` 7.4. Decisions: `ARCHITECTURE.md` 3g.

## How it works

- **Who sees a status is decided on every request** (`PrivacyService`, `VisibilityRules`): the viewer must be in the
  owner's contact list (asked from user-service), not blocked either way, and allowed by the owner's mode. Nothing is
  stored per status, so removing a contact or changing the privacy applies at once, also to statuses already posted.
- **Same 404 for everything** you may not see: expired, unknown, not allowed. Nobody can find out which statuses exist.
- **The feed** (`FeedService`) is built per request in a few steps, each one query or call for the whole feed: contact
  owners from user-service -> their active statuses -> privacy filter -> profiles -> which ones I viewed. The Status page
  asks for it when it opens and every 30 s (no live push).
- **Viewers follow read receipts** (`ViewerService`): a view is always saved, but only listed and counted for the owner
  when the **viewer** has read receipts on; if the **owner** has them off, `viewersHidden` is true and the list is empty.
- **Posting** (`StatusService.create`): validate -> same idempotency key returns the saved status -> at most 30 active
  statuses -> for photos / videos `attach` the upload in media-service with the reference
  `status:<ownerId>:<idempotencyKey>` -> save. Calls to other services never run inside a database transaction. Two
  requests with the same key at the same moment: the unique key lets one win, the other returns the winner's status.
- **Expiry** is a filter on every read (`expires_at > now`) plus `StatusCleanupJob` (every 60 s): it deletes expired
  rows (views go with them, `ON DELETE CASCADE`) and asks media-service to **release** the files. A release that fails is
  stored in `media_releases` and tried again on the next run, so no file is forgotten. A file attached to a message can
  never be released this way (media-service only accepts the exact reference).

## Configuration (environment variables)

`STATUS_DB_URL`, `STATUS_DB_USERNAME`, `STATUS_DB_PASSWORD`, `JWT_SECRET` (same as auth-service), `INTERNAL_SERVICE_TOKEN`,
`USER_SERVICE_URL`, `MEDIA_SERVICE_URL`, `SWAGGER_ENABLED`. Limits in `application.yml`: `chatapp.status.lifetime-seconds`
(86400), `max-active` (30), `cleanup-interval-ms` (60000), `cleanup-batch-size` (100).

## Tests

- Unit: `StatusRequestRulesTest` (field rules per type, colours), `VisibilityRulesTest` (the three privacy modes, blocks),
  `MediaReleaseServiceTest` (retry of failed file releases), `StatusControllerTest` (validation, token handling).
- Integration (real MySQL; fake user-service and media-service): `PostStatusTest` (every type, idempotency, limit,
  attachment errors, dependency down), `VisibilityAndFeedTest` (audience, privacy, blocks, order, 404s), `ViewersTest`
  (read-receipt rules), `PrivacyApiTest`, `LifecycleTest` (delete, expiry clean-up, retry of file release),
  `ShortLifetimeTest` (a status really disappears when its time is over).
- Browser: `frontend/e2e/status.spec.ts`; smoke test: sections "Status", "Status privacy", "Status delete and limit".
