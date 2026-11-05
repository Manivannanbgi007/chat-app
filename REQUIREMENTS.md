# REQUIREMENTS

Functional and non-functional requirements for **chat-app**, a WhatsApp-like real-time messaging platform.
The full original specification is the source of truth; this file is the working summary we build against.

## 1. Functional requirements

### 1.1 Identity (auth-service)
- Register with email and/or phone, password.
- Login with email **or** phone.
- Email verification and OTP verification (OTP expires, resend is limited).
- Forgot / reset password.
- JWT access token (short-lived) + refresh token (rotated on every refresh).
- Logout, token revocation, list and end login sessions (active devices).

### 1.2 Users (user-service)
- Profile: username, display name, email, phone, photo, about, status.
- Search users by username, phone, name.
- Contacts: add, list, remove.
- Block / unblock.
- Privacy settings: last seen, profile photo, about, read receipts, who can add me to groups.

### 1.3 Conversations (conversation-service)
- PRIVATE (1:1) and GROUP conversations.
- Group: add/remove members, leave, make admin, rename, description, image.
- Per-user: archive, mute, pin.
- Only participants can access a conversation.

### 1.4 Messages (message-service)
- Types: TEXT, IMAGE, VIDEO, AUDIO, VOICE, DOCUMENT, LOCATION, CONTACT, SYSTEM.
- Send, edit, delete (soft), reply, forward, react, pin.
- History with cursor pagination, search inside a conversation.
- Receipts per recipient: SENT -> DELIVERED -> READ (FAILED on error).
- Typing indicators.
- Idempotent send (client-generated idempotency key).
- Offline sync: a user who reconnects receives what they missed.

### 1.5 Media (media-service)
- Upload image/video/document/audio/voice; validate type, size, file signature.
- Thumbnails for images.
- Secure (pre-signed) download links; private media is restricted.
- Clean up orphaned/expired media.

### 1.6 Presence (presence-service)
- Online/offline, last seen, multi-device, heartbeat, TTL expiry.
- Respect privacy settings.

### 1.7 Notifications (notification-service)
- New message, missed call, group events, account events.
- History, read/unread, unread count, preferences, device tokens.
- Dedupe, retry, dead-letter.

### 1.8 Calls (call-signaling-service)
- 1:1 audio and video. Initiate / accept / reject / end, busy, ringing timeout.
- WebRTC signaling over WebSocket. Call history and missed calls.

### 1.9 Status / Stories (status-service, Phase 8)
- Text / image / video status, 24h expiry, viewer list, privacy.

### 1.10 Frontend
Login, register, verify, OTP, forgot/reset; chat dashboard; chat UI; composer; groups; contacts; calls; settings (profile, privacy, notifications, theme, blocked, devices, logout); status. Responsive for desktop, tablet, mobile.

## 2. Non-functional requirements

| Area | Requirement |
|---|---|
| Independence | Every service builds, runs, tests and deploys alone. No Maven multi-module. |
| Data ownership | Database per service. No cross-service DB access or FKs. No shared JPA entities. |
| Correctness | Persist before ack. No duplicates on retry. Ordered history. Receipts never skip a state. |
| Security | JWT, BCrypt, refresh rotation, WS auth + destination authorization, rate limit, validation, no secrets in code, no sensitive data in logs. |
| Scalability | Stateless services, Redis coordination, Kafka async processing, horizontal scale. No capacity promise without load tests. |
| Observability | Actuator, Micrometer, correlation ID on every request/event (Prometheus/Grafana/Loki in Phase 9). |
| Readability | Simple layered code, no unnecessary abstractions, understandable by a junior developer. |
| Honesty | TLS + encryption at rest only. **No claim of end-to-end encryption** until a real, reviewed protocol exists. |

## 3. Scope decisions (agreed)
- Java package base: `com.chatapp.<service>`.
- Spring Boot: latest stable line compatible with Spring Cloud Gateway and Spring Kafka; exact version pinned in Phase 1.
- Status is a separate small service in Phase 8.
- Services are created when their phase starts (no empty skeletons).
- Skipped for MVP: Google OAuth, group calls, Avro/Schema Registry, real malware scanning (stub interface only), E2EE.
- MailHog is added to dev Compose for OTP / verification emails.

## 4. Acceptance criteria (initial release)
See section 19 of the original specification. In short: register/login, search, 1:1 and group chat in real time, history, receipts, presence, images/documents, notifications, block, responsive UI, multi-instance real-time, offline sync, independent services, isolated databases, Docker Compose environment, automated tests on critical flows, API + deployment docs.
