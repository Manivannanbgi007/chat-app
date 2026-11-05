# api-gateway

Single entry point for the frontend. Validates access tokens, rate limits, routes requests to the microservices, applies CORS, creates correlation IDs, timeouts and circuit breakers. It contains **no business logic**.

- Port: 8200
- Build and test: `mvn test` (needs Docker: Testcontainers starts Redis)
- Run (IDE mode, from this folder): `mvn spring-boot:run -Dspring-boot.run.profiles=dev`
- Docker image: `docker build -t chat-api-gateway .`
- Swagger UI (aggregated, one entry per service): http://localhost:8200/swagger-ui.html

## Routes
Defined in `application.yml` (localhost defaults) and `application-prod.yml` (service URLs from environment variables, no defaults).

| Path | Service | Rate limit |
|---|---|---|
| `/api/auth/**` | auth-service | per client IP, burst 10 |
| `/api/users/**`, `/api/contacts/**` | user-service | per user, burst 40 |
| `/api/messages/**`, `/api/conversations/{id}/messages`, `/api/conversations/{id}/read` | message-service | per user, burst 60 |
| `/api/conversations/**` (the rest) | conversation-service | per user, burst 40 |
| `/api/presence/**` | presence-service | per user, burst 40 |
| `/api/notifications/**` | notification-service | per user, burst 40 |
| `/api/media/**` | media-service (file uploads up to 100 MB) | per user, burst 20 then 1 per second; own breaker with a 150 s limit |
| `/api/calls/**` | call-signaling-service (Phase 7) | per user, 30 per second, burst 60 (ICE candidates come in bursts while a call connects) |
| `/api/statuses/**` | status-service (Phase 8) | per user, burst 40 |
| `/ws/**` | message-service (WebSocket) | none (no circuit breaker, no time limit) |
| `/service-docs/{auth,user,conversation,message,media,presence,notification,call,status}` | OpenAPI JSON of the service | none |

The order of the routes matters: the message routes under `/api/conversations/...` must be listed **before** conversation-service. Every HTTP route has its own circuit breaker (one broken service cannot block the others). `/ws` is open at the gateway because browsers cannot send a header with the WebSocket handshake; message-service checks the token in the STOMP CONNECT frame. `/internal/**` is never routed.

More routes are added as each service is built.

## What each request goes through
1. `CorrelationIdFilter`: keeps a valid `X-Correlation-Id` or creates one.
2. `JwtAuthenticationFilter`: public endpoints (login, register, ... see the list in the class) pass; everything else needs `Authorization: Bearer <token>` with a valid signature, issuer and expiry, and a login session that was not ended (Redis key `auth:revoked:<sid>`). The token is forwarded unchanged.
3. `RequestRateLimiter` (Redis): 429 when the limit is exceeded.
4. Circuit breaker with a 30 s timeout: 503 in the standard format when a service is down.

## Phase 9 additions
- **Security headers** on every proxied response (`SecureHeaders` default filter): `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`, HSTS. No CSP here (the gateway only answers JSON; the page CSP comes from the frontend's nginx).
- **A token without `sub` gets 401** (it used to end in a 500 inside the per-user rate limiter).
- **Bulkhead:** every route may have at most `chatapp.gateway.max-concurrent-calls` (500) requests in progress; a request that finds no free place waits `chatapp.gateway.bulkhead-wait-ms` (1000) and then gets 503. The library default (25) answered 503 to a busy chat; `ConcurrencyTest` guards this.
- **Why a 503 happened is logged:** `Answering 503 for route message-service: <reason>` (timeout, refused connection, bulkhead, open circuit).
- **Management port:** in Docker the actuator (health, metrics) listens on **8290**, never published (`MANAGEMENT_SERVER_PORT` in the Dockerfile); port 8200 only serves the API. In IDE mode everything stays on 8200.
- Metrics carry the label `application=api-gateway`; HTTP request latencies are published as histograms (p95 in Grafana).

## Environment variables
`AUTH_SERVICE_URL`, `USER_SERVICE_URL`, `CONVERSATION_SERVICE_URL`, `MESSAGE_SERVICE_URL`, `MEDIA_SERVICE_URL`, `PRESENCE_SERVICE_URL`, `NOTIFICATION_SERVICE_URL`, `CALL_SERVICE_URL`, `STATUS_SERVICE_URL`, `MESSAGE_SERVICE_WS_URL` (a `ws://` URL), `CORS_ALLOWED_ORIGINS`, `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD`, `JWT_SECRET` (same as auth-service), `SWAGGER_ENABLED`. Rate limits: `chatapp.ratelimit.auth.*`, `chatapp.ratelimit.api.*`, `chatapp.ratelimit.messages.*`, `chatapp.ratelimit.media.*` and `chatapp.ratelimit.calls.*`.
