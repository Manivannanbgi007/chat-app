# SECURITY DESIGN

## 1. Authentication
- Passwords: BCrypt (strength 10-12). Minimum length and complexity validated; never logged.
- Access token: JWT, 15 minutes, signed with HS256 using the shared `JWT_SECRET` from the environment (Phase 2; at least 32 characters, services refuse to start with a shorter one). RS256 with a key pair, so only auth-service can sign, was NOT done in Phase 9 (open point, see 7i). Claims: `iss` = `chat-app-auth`, `sub` (userId), `roles`, `sid` (session id), `iat`, `exp`. A 60-second clock-skew allowance is Spring's default.
- Refresh token: random 256-bit value, only its **hash** is stored; 7-day expiry; **rotated on every use**. Re-use of an already-rotated token is treated as theft -> revoke the whole session.
- Logout / "end device": mark session revoked in MySQL and set `auth:revoked:{sid}` in Redis (TTL = access token lifetime); gateway and WebSocket interceptor check it.
- Brute force: failed-login counter, temporary lock, gateway rate limit on `/api/auth/**` (strict).
- OTP: 6 digits, stored hashed, 5-minute expiry, max 5 attempts, max resends per hour, constant-time compare.

## 2. Authorization
- Roles: `USER`, `ADMIN` (account-level, in JWT).
- Group admin rights are **conversation-level**, checked in conversation-service.
- Rule 1: user id always comes from the token / WebSocket principal, never from request data.
- Rule 2: every conversation/message/media access checks participation (or ownership) on the server.
- Rule 3: not-found instead of forbidden when existence itself is private.
- Removed group members: `left_at` blocks access to messages created after they left.
- Blocked users: no new PRIVATE conversation, no sending, no presence/profile detail leak.

## 3. Network and transport
- TLS everywhere outside the dev machine (terminate at gateway/load balancer; TLS between services in production).
- Only the gateway (and frontend) are published. Services, MySQL, Redis, Kafka, S3 storage have no public ports in the deployment profile.
- `/internal/**` endpoints are not routed by the gateway and need a service token.
- CORS: explicit allow-list of frontend origins.
- Security headers on frontend host (CSP, X-Content-Type-Options, frame options).

## 4. Input and output
- Bean Validation on all DTOs; request size limits (JSON body, multipart, STOMP frame).
- JPA parameter binding only (no string-built SQL) -> SQL injection protection.
- XSS: React escapes by default; no `dangerouslySetInnerHTML`; message content is plain text; links are not auto-rendered as HTML.
- CSRF: tokens are sent in the `Authorization` header (not cookies), so classic CSRF does not apply. If refresh tokens ever move to HttpOnly cookies, CSRF protection (SameSite + token) must be added.
- Frontend token storage: access token in memory; refresh token in `localStorage` for the MVP "remember me" (documented XSS trade-off); an HttpOnly cookie was NOT done in Phase 9 (open point, see 7i).

## 5. Media
- Allow-list of MIME types, size limits, **file signature (magic number) check**, sanitized stored names (object key = UUID).
- Private bucket; downloads only through short-lived pre-signed URLs after an access check.
- `VirusScanner` interface (no-op now, real scanner before production).

## 6. Logging and audit
- Never log: passwords, JWTs, refresh tokens, OTPs, message content, attachment contents.
- Log ids and correlation ids only.
- Audit log (auth_db) for register, login success/failure, password change/reset, session revoke, block/unblock.

## 7. Secrets
- No secrets in code or committed config. Environment variables only; `.env.example` has placeholders; `.env` is git-ignored.
- Production: AWS Secrets Manager / Parameter Store.

## 7b. What Phase 2 actually implements
- Gateway: rejects missing/invalid/expired tokens and tokens of ended sessions (Redis key `auth:revoked:{sid}`); if Redis is down the revocation check is skipped (fail open, token lifetime is only 15 minutes) and a warning is logged.
- Auth-service: BCrypt (cost 10) passwords; refresh tokens and reset tokens stored only as SHA-256 hashes; OTP stored as a BCrypt hash; refresh rotation with reuse detection (a replayed old token ends the session); account locked for 15 minutes after 5 failed logins; OTP: 6 digits, 10 minutes, 5 attempts, 60 s between sends, 5 per hour; login answers are identical for unknown user and wrong password (a dummy BCrypt check keeps timing similar); forgot-password always answers the same; audit log for register, verify, login success/failure, lockout, token reuse, logout, session end, reset request, reset.
- Known limitation: `resend-otp` returns 429 only for existing unverified accounts, which leaks that the account exists after repeated calls. Accepted for the MVP.
- Known limitation: the client IP used for rate limiting and audit comes from `X-Forwarded-For`. The gateway trusts exactly one proxy (the last entry), so it must only be reachable through that proxy in production; in local Docker the gateway port is published, so a caller can fake the address.
- Refresh tokens are kept in `localStorage` (or `sessionStorage` without "remember me") by the frontend; an XSS bug could steal them. Moving to an HttpOnly cookie is an open point (7i); the Phase 9 Content-Security-Policy makes such a bug harder to exploit.

## 7c. What Phase 3 adds
- **Internal endpoints** (`/internal/**` in user-service and conversation-service) need `X-Internal-Token`, compared in constant time; services refuse to start with a token shorter than 24 characters. They are not routed by the gateway; only the Docker network can reach them. The internal token is a shared secret between services, not a user credential.
- **WebSocket:** the token travels in the STOMP CONNECT frame, never in the URL. Invalid, expired, wrong-issuer and revoked-session tokens are refused. Every 30 s the server closes sockets whose token expired or whose session ended, and sockets that never sent CONNECT. Only `/app/chat.*` can be sent to; only the user's own queues and `/topic/conversation/{id}` (for participants) can be subscribed to (Phase 4 removed the topic, see 7d). Sends are limited to 30 per 10 s and typing to 1 per second per connection; frames are limited to 64 KB.
- **Conversations and messages:** the sender is always the token's `sub` (DTOs have no sender field). A user who is not a participant gets the same 404 as for an unknown conversation. Blocking in either direction gives the same 403 `MESSAGING_NOT_ALLOWED`, so a blocked user cannot tell who blocked whom. Message text is limited to 4000 characters, only type TEXT is accepted until media arrives.
- **Receipts:** only a recipient can acknowledge, only forward, per device; "read" is hidden from the sender when either user turned read receipts off (applied when shown). Message text is never put into Kafka events or logs.
- Known limitation: a message-service instance trusts the Redis channel `ws.events` completely. Redis must therefore only be reachable by the services (it has a password and no published port in a real deployment).

## 7d. What Phase 4 adds
- **Group authorization lives in conversation-service** and is checked inside a transaction that locks the conversation row: only admins add or remove members, change roles and change settings; with `onlyAdminsCanEdit` only admins rename or change the description. Nobody can change their own role or remove themselves through the admin endpoints (leave instead). A group never ends up without an admin while members remain.
- **Being added to groups** follows the target's privacy setting `groupAdd` (EVERYONE / CONTACTS / NOBODY) and the block list (either direction), checked by user-service. Unknown users, blocked users and privacy refusals give the same `GROUP_ADD_NOT_ALLOWED`, so the answer does not reveal which one applies.
- **Removed-member rule:** messages are only returned, acknowledged or explained (receipt details) when they fall inside one of the reader's membership periods. Former members get the same 404 as strangers when sending. A former member's view of the group hides the current member list and admins.
- **Sending in groups:** current members only; `onlyAdminsCanSend` is enforced by message-service with the admin list from conversation-service (asked on every send, no cache). SYSTEM messages can only be written by the server (clients may only send TEXT).
- **Typing** is delivered per user to current members only (`/user/queue/typing`); the per-conversation topic, which a removed member could have kept listening to, no longer exists.
- **Receipt details** (`/api/messages/{id}/receipts`) are visible to the sender only.
- Known limitation: a member removed while a send is in flight may still receive that one message live (see ARCHITECTURE.md 3c).

## 7e. What Phase 5 adds (media)
- **Never trust the file's claims.** Type is detected from the file's own bytes (magic numbers), compared with the extension and the browser's Content-Type, and checked against the allow-list of the chosen purpose. Executables, scripts, HTML, SVG and archives are refused whatever the name says; plain text that looks like HTML / SVG / XML is refused too. Office files are opened as zip only with entry-count and size caps (zip-bomb guard). The stored type is the **detected** one.
- **Images:** dimensions are read from the header without decoding the image, and absurd sizes (more than 12000 px per side or 50 million pixels) are refused before any decoding. Thumbnails are decoded with subsampling to keep memory small.
- **Limits at every layer:** per-purpose size limits in media-service, `client_max_body_size 110m` in nginx, a dedicated gateway route (own circuit breaker, 150 s, rate limit 20 uploads at once then 1 per second per user). Files are streamed to the storage, never held in memory.
- **Storage:** private bucket, random object keys (`yyyy/MM/<uuid>`), nothing public. Downloads only through signed links valid for 5 minutes, with the content type and `Content-Disposition` forced in the link (documents download, they never render in the browser), so an uploaded file cannot be served as a web page.
- **Access:** the uploader sees their own unattached upload; everybody else can only reach a file through a **message** they may see (removed-member rule). A file belongs to at most one message, can only be attached by the person who uploaded it, and an unknown file and somebody else's file give the same 404.
- **Hygiene:** file names are cleaned (no path, no control characters, 150 characters), uploads that are never sent are deleted after 24 hours, every upload / link / attach is written to an access log (who, which file, when; never the content).
- **Known limits:** no real malware scanning yet (a `VirusScanner` interface exists, the default scans nothing: **replace it before production**); the signed link is a bearer link, anyone who gets the URL can use it for 5 minutes; the storage address in the link is the public one, so in production it must be HTTPS (S3 / CDN).

## 7f. What Phase 6 adds (presence and notifications)
- **Presence privacy:** the "last seen / online" setting is applied on the server for every read (`GET /api/presence/{id}`): blocked users, `NOBODY`, and `CONTACTS` are enforced by user-service's answer; a hidden status returns `null` fields, never a guess. Reciprocity: someone who hides their own status cannot see others'. The internal `online` lookup used by notification-service ignores privacy on purpose and is internal-token only.
- **Notifications never contain message text**, only who and how many; group notifications contain the group name and the actor's display name. A user can only list, read or change **their own** notifications (other ids give 404). Device tokens belong to the token's user and a token can only belong to one user at a time; removing it on logout stops pushes to that browser.
- **Preferences are enforced server-side** (push off / messages off / groups off); muting a chat suppresses its notifications (conversation-service decides).
- **Redis `ws.events`:** now written by two services. Anything that can write to that channel can push events to any user's socket, so Redis stays private (password, no published port in production).
- **Rate limits:** `/api/presence/**` and `/api/notifications/**` are limited per user at the gateway. Heartbeats are cheap (one Redis write).
- Known limits: presence can lag up to 60 s after a crash; the browser "local" notification shows the sender's display name on the user's own screen only; the FCM sender is a stub.

## 7g. What Phase 7 adds (calls)
- **Who may call:** anyone who is not blocked either way (checked with user-service before anything is saved; the same `CALL_NOT_ALLOWED` whoever blocked whom). Every call endpoint answers 404 to anyone who is not the caller or the callee.
- **Devices:** only the device (login session `sid` from the token) that created the call, and the one that accepted it, may send signals or pings. Another device of the same user gets 403 `NOT_CALL_DEVICE`.
- **Signaling is relayed, not stored:** SDP and ICE candidates (they contain IP addresses) go only to the other participant's call device, are size-limited (SDP 20 000 characters, 50 candidates per request) and never written to the database or the logs.
- **Media privacy:** audio and video are encrypted by WebRTC itself (DTLS-SRTP, mandatory in browsers) between the two browsers. A TURN relay forwards encrypted packets it cannot read. This is transport encryption between two browsers set up through our server, **not** an audited end-to-end encryption claim: a malicious signaling server could still insert itself (no key verification UI).
- **TURN credentials** are short-lived (8 hours) and per user: `username = expiry:userId`, `password = base64(HMAC-SHA1(TURN_SECRET, username))`. Coturn checks them with the same secret; no TURN passwords are stored. `TURN_SECRET` is an environment variable like every other secret.
- **Coturn hardening:** TCP relaying and multicast peers are off. Production settings (done in Phase 9, `docker-compose.prod.yml`): external IP, `denied-peer-ip` for every private range so the TURN server cannot be used to reach internal services, and quotas; TLS (`turns:` on 5349) is still open (see 7i). Locally the relay must reach the Docker network, so that list is not set.
- **Abuse limits:** the gateway limits `/api/calls/**` per user (30 requests per second, burst 60, ICE batches included); a user can be in one call at a time; ringing ends after 30 s.
- Known limitation: a user who keeps calling someone is only stopped by blocking (no per-pair call rate limit yet).

## 7h. What Phase 8 adds (status / stories)
- **Who sees a status** is decided on every request by status-service with fresh answers from user-service: the viewer must be in the owner's contact list, nobody may have blocked the other, and the owner's status privacy (all contacts / contacts except / only share with) must allow it. An unknown, expired, deleted or hidden status gives the same 404 everywhere (view, media link, viewers), so nobody can probe for status ids.
- **Media** of a status is only reachable through 5-minute signed links that status-service asks for after that check (the same model as message attachments). Status files are attached with the reference `status:<ownerId>:<idempotencyKey>`; the new release call deletes a file only with exactly that reference and owner, so status-service cannot delete anybody's message files.
- **Nothing is kept after 24 h:** expired statuses are filtered out of every query and deleted together with their views and files; a deleted status is removed at once.
- **Viewer lists** follow read receipts (a viewer with read receipts off is not listed; an owner with read receipts off sees no viewers), and only the owner can ask for them.
- Limits: 30 active statuses per user, text and captions 700 characters, background colours from a fixed list, uploads through the normal media checks (type from the file's bytes, size limits).
- Known limitation: the audience is decided when someone looks, not when the status is posted, so adding someone to your contacts also shows them statuses you posted before.

## 7i. Phase 9: security review

**How it was done.** (1) `infra/scripts/security-check.py`: 103 black-box checks that attack the running stack from outside (only through the
gateway and the frontend), also against the production setup with HTTPS. (2) Trivy scans of the dependencies and of all 11 Docker images, and
`npm audit`. (3) A read-through of every configuration that differs between development and production. (4) The load test, which also finds
availability problems. Each finding below was fixed and has a test or a check that fails without the fix.

| # | Finding | Where found | Fix |
|---|---|---|---|
| 1 | A correctly signed token **without `sub`** ended in HTTP 500 inside the per-user rate limiter | security-check | gateway rejects it with 401 (`JwtAuthenticationTest.tokenWithoutSubjectIsRejected`) |
| 2 | The gateway sent no security headers | security-check | `SecureHeaders` default filter: nosniff, X-Frame-Options, Referrer-Policy, HSTS |
| 3 | The frontend's nginx sent **no** Content-Security-Policy / nosniff / frame / referrer / permissions headers, announced its version, and ran as root | security-check, image review | CSP (scripts only from the site, no inline script, no eval, no framing, files only from the storage address), nosniff, `X-Frame-Options: DENY`, `Referrer-Policy: no-referrer`, `Permissions-Policy` (camera / microphone only for the site), COOP, `server_tokens off`; the unprivileged nginx image (user `nginx`, port 8080) |
| 4 | Dependencies with **HIGH** vulnerabilities: `lz4-java` and `snappy-java` (through the Kafka client, 6 services), `httpcore5` (through the AWS SDK, media-service) | Trivy | pinned to the fixed versions in the poms (comment says when to remove) |
| 5 | Alpine packages with HIGH / CRITICAL vulnerabilities in the images (libexpat, curl, brotli) | Trivy | `apk upgrade` in every Dockerfile. **Result: 0 fixable HIGH or CRITICAL findings in all 11 images, `npm audit` 0** (the CI repeats the scan every night and fails a release on a fixable CRITICAL one) |
| 6 | The gateway silently allowed only **25 requests at the same time per route** (library default "bulkhead") and answered 503 to the rest: with 50 chatting pairs 8.5 % of the requests failed, and nothing was logged | load test | limit 500 (`chatapp.gateway.max-concurrent-calls`), `ConcurrencyTest` (60 parallel requests; fails with the old limit), and the 503 fallback now logs route and reason |
| 7 | Behind a second proxy the gateway would have counted **all users as one IP** for the login rate limit (it trusts the last `X-Forwarded-For` entry) | design review of the production proxy | in production Caddy sends `/api` and `/ws` straight to the gateway (no nginx in between) |
| 8 | The gateway's actuator (metrics, health) sat on the **public** port | review | in Docker the gateway's management endpoints are on the internal port 8290; services expose `/actuator/prometheus` only inside the Docker network (the gateway has no route to it) |
| 9 | Development defaults that must not reach production: Swagger on in every service, MailHog / Kafka UI, every infrastructure port published, a fixed storage access key, SMTP without login or TLS | review | `docker-compose.prod.yml`: Swagger off, MailHog / Kafka UI only with profile `dev-only`, only 80 / 443 and Coturn published, random storage key, SMTP username / password / STARTTLS settings in auth-service |
| 10 | Containers had all Linux capabilities, a writable root file system, no log limits | review | every Java service: `cap_drop: ALL`, `no-new-privileges`, read-only root file system with a 64 MB in-memory `/tmp` (media-service keeps a normal disk because uploads are buffered), non-root user (uid 10001) |
| 11 | The malware scanner scanned nothing | known since Phase 5 | real `ClamAvVirusScanner` (clamd INSTREAM), **fail closed**; see below |
| 12 | Coturn could relay to any address (it could be used to reach internal services) | known since Phase 7 | production: `--denied-peer-ip` for all private / loopback / link-local / multicast ranges, `--external-ip`, quotas per user. Proven: with it on, Coturn logs `A peer IP 172.20.0.4 denied` and refuses to relay |

**Checked and found correct (no finding):** all 24 protected routes answer 401 without a token; forged tokens (`alg=none`, wrong secret, wrong issuer,
expired, no subject) are refused by the gateway and the WebSocket; `/internal/**` and dangerous actuator endpoints are not reachable from outside;
CORS only allows the frontend origin (never `*`); malformed JSON and unknown paths give clean errors without stack traces; SQL-looking input
gives 400/401; a 3 MB body is refused cleanly; wrong password and unknown account (and forgot-password for a known and an unknown address) give
identical answers; a weak password is refused; a stranger cannot read, send, mark read, change settings, add members to, rename or ask attachment
links of somebody else's conversation, cannot see, mark viewed, download, list viewers of or delete somebody else's status; STOMP CONNECT without
token / with garbage / forged / expired token is refused; logout kills both the refresh token and the access token at once. The frontend uses no
`dangerouslySetInnerHTML`, no `innerHTML`, no `eval` and loads nothing from other websites.

**Malware scanning (ClamAV).** media-service sends every upload to the `clamd` daemon before it is stored (`VIRUS_SCANNER=clamav`). An infected file gives
400 `MALWARE_DETECTED` (the signature name is only in the log). If ClamAV is down or answers with an error, the upload is **refused with 503**; an unscanned file
is never stored. Tried with a real ClamAV container: a clean image is accepted, the EICAR test file is rejected. Be aware of the limit of any scanner: the standard
test string hidden as a comment inside a PDF-looking file was **not** detected by ClamAV's PDF handling (the file's type is checked by its first bytes
anyway). A scanner reduces risk; it does not prove a file is safe. Files are only ever served through short-lived signed links with
`Content-Disposition` handled by the storage and are never executed by the server.

**Open points (accepted for now, written down so they are not forgotten):**
- **HS256 shared secret.** Every service that holds `JWT_SECRET` could mint tokens. RS256 (only auth-service signs, the others only hold the public key)
  removes that; it touches all 10 services and their test helpers, so it was not done in this phase.
- **Refresh token in `localStorage`.** A cross-site-scripting bug could steal it. The new Content-Security-Policy makes such a bug much harder to exploit, but an
  HttpOnly cookie (auth-service + gateway + frontend change) would remove the risk.
- **One `INTERNAL_SERVICE_TOKEN` for all service-to-service calls.** A compromised service can call every internal endpoint. Per-service tokens or mTLS are the next step.
- **Promtail reads the Docker socket** (read-only) to find container logs. Anything that takes over Promtail can read container metadata and environments. If that
  worries you, run a `docker-socket-proxy` in front of it or use Docker's Loki logging driver.
- **No DDoS protection** beyond the gateway's rate limits and Caddy: use your provider's network protection for a public site.
- **TURN over TLS** is not enabled (see 7g), the CSP needs `style-src 'unsafe-inline'` because React sets inline style attributes, and Redis being down makes the gateway skip the
  "revoked session" check (tokens then live at most 15 more minutes).
- The ClamAV limitation above, and no end-to-end encryption (section 9).

## 8. Rate limiting (gateway, Redis token bucket)
Spring Cloud Gateway's Redis limiter works in tokens per second (refill rate + burst), so limits are expressed that way.

| Scope | Limit (Phase 2) |
|---|---|
| `/api/auth/**` | refill 2/s, burst 10, per client IP |
| `/api/users/**`, `/api/contacts/**`, `/api/conversations` (list/create/get) | refill 20/s, burst 40, per user (token `sub`) |
| `/api/messages/**`, `/api/conversations/{id}/messages`, `/api/conversations/{id}/read` | refill 30/s, burst 60, per user |
| WebSocket sends | 30 per 10 s per connection (message-service) |
| OTP resend / reset mails | enforced in auth-service: 60 s between codes, 5 per hour |
| Media upload | later (Phase 5) |
| STOMP send | later (Phase 3) |

Exceeding a limit returns 429 with `X-RateLimit-*` headers. The values are configurable (`chatapp.ratelimit.*`).

## 9. Encryption statement
Data is protected **in transit (TLS)** and **at rest** (encrypted disks / RDS / S3 encryption). The server can read message content. This is **not** end-to-end encryption and must not be advertised as such. E2EE is a separate future phase using an established, audited protocol (e.g. Signal protocol libraries) - no custom cryptography.

## 10. Security test checklist (Phase 9)
Dependency scan, auth bypass attempts on each route, IDOR tests on conversation/message/media ids, WebSocket subscribe to foreign conversation, expired/revoked token on socket, upload of disguised files, rate-limit verification, privacy-setting bypass tests.

**Result (Phase 9):** all items are covered by `infra/scripts/security-check.py` (103 checks: auth bypass on every route, IDOR on conversations / messages / receipts / media links / statuses, WebSocket with no / bad / forged / expired token, rate-limit and account-enumeration checks, headers, CORS, hostile input), Trivy and `npm audit` (dependencies), the existing smoke test (disguised files, rate limit) and the per-service privacy tests. Run it after every change of the gateway, the proxy or the frontend container:  `python infra/scripts/security-check.py` (add `--gateway https://<domain> --frontend https://<domain> --origin https://<domain>` for production).
