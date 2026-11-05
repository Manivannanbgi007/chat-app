# DEVELOPMENT GUIDE

For developers new to the project. Read `ARCHITECTURE.md` first for the big picture.

## 1. Prerequisites
Java 21, Maven 3.9+, Node 24+, Docker Desktop. See `DOCKER_SETUP.md` to start the infrastructure.

## 2. Repository map
```
infra/                  Docker Compose files, .env.example, MySQL init script
services/api-gateway/   routes requests, CORS, correlation IDs (no business logic)
services/auth-service/  registration, login, tokens, OTP, password reset, sessions
services/user-service/  profiles, contacts, blocking, privacy (profile is created from Kafka)
services/conversation-service/  chats: one-to-one and groups, members, admins, mute / archive / pin
services/message-service/  messages, receipts, WebSocket (STOMP), SYSTEM messages for group changes
frontend/               React app
*.md                    design documents
```
Each service is an **independent Maven project** (its own `pom.xml`, no parent or multi-module). Build one service at a time:
```bash
cd services/auth-service
mvn test
mvn package
```

## 3. Standard layout inside a service
```
com.chatapp.<service>/
  config/       Spring configuration, filters
  controller/   REST endpoints (thin: validate, call a service, return a DTO)
  dto/request/, dto/response/   what goes in and out of the API (never expose entities)
  entity/       JPA classes mapped to tables
  repository/   Spring Data interfaces
  service/      business rules (interface + impl when it helps)
  exception/    ApiException types + GlobalExceptionHandler
  mapper/       MapStruct entity <-> DTO
  kafka/, websocket/, security/ ...   added only when needed
```
Rule of thumb: controllers do no business logic; services do no HTTP; repositories do no business rules.

## 4. Conventions every service follows
1. **Response format**: every endpoint returns `ApiResponseDTO<T>` (`success`, `message`, `data`, `timestamp`). Errors too.
2. **Errors**: throw `ApiException` subclasses (e.g. `ResourceNotFoundException`) from services. `GlobalExceptionHandler` converts them. Never catch-and-build responses in controllers.
3. **Validation**: put Bean Validation annotations on request DTOs, use `@Valid` in controllers.
4. **Correlation ID**: `CorrelationIdFilter` puts `X-Correlation-Id` in the log context. Logs show it automatically.
5. **Database changes**: only through Flyway files in `src/main/resources/db/migration` named `V<number>__description.sql`. Never edit a migration that has been run; add a new one. Hibernate is set to `validate` and only checks the schema.
6. **Config**: `application.yml` (defaults for local), `application-dev.yml`, `application-prod.yml` (no defaults; fails fast if an environment variable is missing). No secrets in files - environment variables only.
7. **Never log**: passwords, tokens, OTPs, message content.
8. **No shared code between services.** Small classes such as `ApiResponseDTO` are copied on purpose. A new service starts by copying `auth-service`'s foundation (envelope, exception handler, correlation filter, config, Dockerfile).

## 4b. More conventions from Phase 2
9. **Identity**: the caller is always the JWT `sub` (`@AuthenticationPrincipal Jwt`). Never read a user id from a request body, query or path to decide *who is acting*. Ids in the path say *what* is acted on and are always checked against the caller.
10. **Business error codes**: throw `ConflictException("...", "EMAIL_TAKEN")` style exceptions. The code goes to the client in `data.errorCode`, so the frontend never parses messages.
11. **Kafka events**: write them with `OutboxService.queue(...)` inside the same `@Transactional` method that saves the data (see `RegistrationService`). Never call `KafkaTemplate` from business code. Consumers must be idempotent: record the `eventId` in `processed_events` in the same transaction (see user-service `UserEventConsumer`).
12. **Methods that throw after saving something** (failed-login counter, revoked session) need `@Transactional(noRollbackFor = ApiException.class)`, otherwise the save is rolled back with the error.
13. **Security rules**: which URLs are public lives in one place per service (`SecurityConfig`) and, for the whole system, in the gateway's `JwtAuthenticationFilter`. A new public endpoint must be added to both, with a test.
14. **Secrets**: only from environment variables (`JWT_SECRET`, passwords). Services refuse to start with a weak `JWT_SECRET`.

## 5. How to add a new service (checklist)
1. Copy the foundation from `auth-service` (pom, application class, config, exception, dto/response, application*.yml, Dockerfile, `.dockerignore`), rename the package to `com.chatapp.<name>`.
2. Pick the port from `ARCHITECTURE.md`; create its database/user in `infra/mysql/init/01-create-databases.sh` and its password in `infra/.env.example`.
3. Add a route in `api-gateway` (`application.yml` and `application-prod.yml`) and a Swagger entry.
4. Add the service to `infra/docker-compose.yml`.
5. Write unit + controller tests, and an integration test with Testcontainers if it uses MySQL.
6. Update `IMPLEMENTATION_CHECKLIST.md`.

## 6. Testing
- `@WebMvcTest` for controllers (fast, no database).
- Plain JUnit/Mockito for service classes.
- `@SpringBootTest` + Testcontainers for database/Kafka/Redis integration. Docker must be running.
- Frontend: `npm test` (Vitest + React Testing Library).

## 7. Frontend
See `frontend/README.md`. The API client (`src/services/api`) is the only place that talks HTTP. It adds the correlation ID and token and turns every error into one `AppError` type.

## 8. Troubleshooting
| Problem | Fix |
|---|---|
| `Could not resolve placeholder 'AUTH_DB_PASSWORD'` | `infra/.env` missing (run `bash infra/init-env.sh`) or the service was started from the wrong folder |
| `Access denied for user 'auth_user'` | The MySQL volume was created with an older `.env`. `down -v` and start again (deletes data) |
| Port already in use | Another project uses it. Ports are listed in `ARCHITECTURE.md` |
| Testcontainers cannot find Docker | Start Docker Desktop |
| Gateway returns 503 | The target service is down (circuit breaker fallback) |
