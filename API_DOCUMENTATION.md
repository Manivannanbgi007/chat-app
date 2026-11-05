# API DOCUMENTATION

All calls go through the gateway (`http://localhost:8200`). Each service also publishes OpenAPI (Swagger) in the `dev` profile; this file is the contract summary. Endpoints are added to the checklist as phases complete.

## 1. Conventions

**Auth header:** `Authorization: Bearer <accessToken>` on everything except public auth endpoints.
**Correlation:** optional `X-Correlation-Id` request header; always returned in the response.
**Identity:** the caller's user id comes from the verified JWT, never from the body.

**Response envelope (`ApiResponseDTO`)**
```json
{ "success": true, "message": "Operation completed successfully", "data": {}, "timestamp": "2026-11-04T10:00:00Z" }
```
Error example:
```json
{ "success": false, "message": "Validation failed", "data": { "email": "must be a valid email" }, "timestamp": "..." }
```

| Status | Used for |
|---|---|
| 200 / 201 / 204 | OK / created / no content |
| 400 | validation errors, bad request |
| 401 | missing/invalid/expired token |
| 403 | authenticated but not allowed (not a participant, blocked, not admin) |
| 404 | not found (also used instead of 403 when revealing existence is a leak) |
| 409 | conflict (duplicate email, already member) |
| 413 / 415 | file too large / unsupported type |
| 429 | rate limited (with `Retry-After`) |
| 500 / 503 | server error / downstream unavailable |

**Pagination (messages):** `GET .../messages?limit=30&before=<cursor>` returns
```json
{ "items": [ ... ], "nextCursor": "opaque-string-or-null", "hasMore": true }
```
Items are newest-first per page; the client reverses for display. `after=<cursor>` is used for sync after reconnect.

## 2. Auth (auth-service)

**Field rules (validated with Bean Validation, 400 + field map on failure)**
- `username`: 3-30 chars, lowercase letters, digits, `_` or `.` (input is lowercased).
- `email`: valid email, max 254 (lowercased).
- `phone` (optional): E.164, e.g. `+919876543210` (`^\+[1-9]\d{7,14}$`).
- `password`: 8-72 chars, at least one letter and one digit.
- `displayName`: 1-50 chars.
- `otp`: exactly 6 digits.

**Error codes.** Business errors carry a machine-readable code in `data`: `{ "errorCode": "EMAIL_TAKEN" }`. Validation errors (400) put the field map in `data` instead.

| Method | Path | Request body | Success | Errors (status: errorCode) | Public |
|---|---|---|---|---|---|
| POST | /api/auth/register | `{ username, email, phone?, password, displayName }` | 201 `{ userId, email, verificationRequired: true }` | 409: `EMAIL_TAKEN`, `PHONE_TAKEN`, `USERNAME_TAKEN` | yes |
| POST | /api/auth/verify-otp | `{ identifier, otp, purpose: "EMAIL_VERIFY" }` | 200 `null` | 400: `INVALID_OTP`; 429: `OTP_ATTEMPTS_EXCEEDED` | yes |
| POST | /api/auth/resend-otp | `{ identifier, purpose: "EMAIL_VERIFY" }` | 200 `null` (same answer for unknown identifiers) | 429: `OTP_RATE_LIMITED` | yes |
| POST | /api/auth/login | `{ identifier (email, phone number or username), password, deviceName? }` | 200 `AuthTokens` (below) | 401: `INVALID_CREDENTIALS`; 403: `EMAIL_NOT_VERIFIED`; 423: `ACCOUNT_LOCKED` | yes |
| POST | /api/auth/refresh | `{ refreshToken }` | 200 `AuthTokens` | 401: `INVALID_REFRESH_TOKEN` | yes |
| POST | /api/auth/logout | `{ refreshToken }` (ends that device session; always 200) | 200 `null` | | yes |
| POST | /api/auth/forgot-password | `{ identifier }` | 200 `null` (always, no user enumeration; emails a reset link) | | yes |
| POST | /api/auth/reset-password | `{ token, newPassword }` | 200 `null` (ends all sessions) | 400: `INVALID_RESET_TOKEN` | yes |
| GET | /api/auth/sessions | | 200 `[ { id, deviceName, ipAddress, createdAt, lastActiveAt, current } ]` | | no |
| DELETE | /api/auth/sessions/{sessionId} | | 200 `null` | 404 when not yours | no |
| GET | /api/auth/ping | | 200 `{ service, status }` (Status page) | | yes |

`AuthTokens` = `{ accessToken, refreshToken, tokenType: "Bearer", expiresIn: 900, user: { id, username, displayName, email } }`.

**Tokens**
- Access token: JWT signed HS256 with the shared `JWT_SECRET`, valid 15 minutes. Claims: `iss` = `chat-app-auth`, `sub` = user id (UUID), `sid` = login session id, `roles` = `["USER"]`, `iat`, `exp`.
- Refresh token: opaque random string, valid 7 days, **rotated on every refresh** (the old one stops working). Using an already-used refresh token ends that whole session (theft protection). Clients must therefore never send two refresh calls at the same time.
- Registration does not log the user in. The user must verify the email with the 6-digit code (valid 10 minutes, max 5 attempts, max 5 codes per hour, at least 60 s between codes), then log in.
- In local development the emails arrive in MailHog (http://localhost:8025). Phone verification (SMS) is not implemented.
- 5 failed logins lock the account for 15 minutes.

## 3. Users (user-service)

All user endpoints need `Authorization: Bearer <accessToken>`; the user id is always the token's `sub`. The profile is created asynchronously from `UserRegisteredEvent` (normally within a second of registering).

| Method | Path | Request | Success | Notes |
|---|---|---|---|---|
| GET | /api/users/me | | 200 `MyProfile` | 404 if the profile was not created yet |
| PUT | /api/users/me | `{ displayName (1-50), about? (max 139) }` | 200 `MyProfile` | profile photo upload arrives with media-service (Phase 5) |
| GET | /api/users/search?q= | `q` 2-50 chars (400 otherwise) | 200 `[PublicProfile]` max 20 | matches username prefix, display-name contains, exact phone; excludes me and users who blocked me |
| GET | /api/users/{userId} | | 200 `PublicProfile` | 404 if unknown or the user blocked me; `about` / `profileImageUrl` are `null` when privacy hides them from me |
| POST | /api/users/{userId}/block | | 200 `null` | idempotent; cannot block yourself (400); unknown user 404 |
| DELETE | /api/users/{userId}/block | | 200 `null` | idempotent |
| GET | /api/users/blocked | | 200 `[PublicProfile]` | |
| GET | /api/users/me/privacy | | 200 `Privacy` | |
| PUT | /api/users/me/privacy | `Privacy` | 200 `Privacy` | |
| POST | /api/contacts | `{ contactUserId, nickname? (max 50) }` | 201 `Contact` | 400 add yourself; 404 unknown/blocked-me; 409 `ALREADY_CONTACT` |
| GET | /api/contacts | | 200 `[Contact]` sorted by name | |
| DELETE | /api/contacts/{contactId} | | 200 `null` | 404 if not yours |

Shapes:
- `MyProfile` = `{ id, username, displayName, email, phone, profileImageUrl, about, accountStatus, createdAt, updatedAt }`
- `PublicProfile` = `{ id, username, displayName, profileImageUrl, about }` (never email or phone)
- `Contact` = `{ id, contactUserId, nickname, createdAt, user: PublicProfile }`
- `Privacy` = `{ lastSeen, profilePhoto, about, groupAdd: "EVERYONE" | "CONTACTS" | "NOBODY", readReceipts: boolean }`. "CONTACTS" means the owner has the viewer in their own contact list. Defaults: all `EVERYONE`, `readReceipts` true.

Event consumed by user-service: topic `user.events`, key = userId, JSON envelope `{ eventId, eventType: "UserRegisteredEvent", aggregateId: userId, occurredAt, schemaVersion: 1, correlationId, payload: { userId, username, email, phone, displayName } }`.

## 4. Conversations (conversation-service)

One-to-one chats (Phase 3), groups and per-user mute / archive / pin (Phase 4). All need a bearer token; the caller is the token `sub`.

### 4.1 Shapes
```
Conversation = {
  id, type: "PRIVATE" | "GROUP", createdAt, lastMessageAt (null until the first message),
  participants: [PublicProfile],     // the current members (for a group I am no longer in: [])
  otherUser: PublicProfile | null,   // PRIVATE: the participant who is not me. GROUP: null
  group: GroupInfo | null,           // GROUP only
  membership:  { role: "MEMBER" | "ADMIN", active: boolean, joinedAt, leftAt },  // MY membership
  preferences: { mutedUntil: string | null, archived: boolean, pinned: boolean }  // MY settings for this chat
}
GroupInfo = { name, description (or null), imageUrl (null until media arrives in Phase 5), createdBy,
              onlyAdminsCanSend, onlyAdminsCanEdit, adminIds: [uuid] }
```
- `PublicProfile` as in section 3, filtered by each user's privacy settings for me.
- A group I left or was removed from: `membership.active = false`, `participants = []`, `group.adminIds = []` (who is in the group now is not shown any more). Name and description are the current ones.
- `preferences.mutedUntil`: the chat is muted while this time is in the future. "Always" is stored as `9999-12-31T23:59:59Z`.
- PRIVATE chats: `membership.role` is always `MEMBER`, `active` always `true`.
- Message text is **not** stored in conversation-service. The chat list gets the last message and unread count from message-service (`GET /api/messages/summaries`) and merges by `conversationId`.

### 4.2 Endpoints

| Method | Path | Request | Success | Errors (status: errorCode) |
|---|---|---|---|---|
| POST | /api/conversations | PRIVATE: `{ type: "PRIVATE", userId }` | 201 `Conversation` (200 with the existing one when this pair already has a conversation) | 400 talking to yourself; 404 unknown user; 403 `MESSAGING_NOT_ALLOWED` if either user blocked the other |
| POST | /api/conversations | GROUP: `{ type: "GROUP", name (1-100 chars, trimmed), description? (max 500), memberIds: [uuid] }` | 201 `Conversation`; I am ADMIN, the others MEMBER | 400 validation (no other member left after removing me and duplicates); 403 `GROUP_ADD_NOT_ALLOWED` + `userIds`; 409 `GROUP_FULL` |
| GET | /api/conversations | | 200 `[Conversation]` every chat I am or was in (archived ones and groups I left too), newest activity first (`lastMessageAt`, then `createdAt`). Pinned-first and the archived folder are the client's job | |
| GET | /api/conversations/{id} | | 200 `Conversation` | 404 if it does not exist **or I was never a participant** |
| PATCH | /api/conversations/{id}/group | `{ name?, description?, onlyAdminsCanSend?, onlyAdminsCanEdit? }` (at least one; `description: ""` clears it) | 200 `Conversation` | 400 `NOT_A_GROUP`; 403 `NOT_A_MEMBER`; 403 `NOT_GROUP_ADMIN` (settings always need an admin; name/description need an admin only when `onlyAdminsCanEdit`) |
| POST | /api/conversations/{id}/members | `{ userIds: [uuid] (1-50) }` | 200 `Conversation` | 403 `NOT_GROUP_ADMIN`; 403 `GROUP_ADD_NOT_ALLOWED` + `userIds`; 409 `ALREADY_MEMBER` (all of them already are); 409 `GROUP_FULL` |
| DELETE | /api/conversations/{id}/members/{userId} | | 200 `Conversation` | 403 `NOT_GROUP_ADMIN`; 400 `CANNOT_CHANGE_SELF` (use leave); 404 not a current member |
| PUT | /api/conversations/{id}/members/{userId}/role | `{ role: "ADMIN" \| "MEMBER" }` | 200 `Conversation` | 403 `NOT_GROUP_ADMIN`; 400 `CANNOT_CHANGE_SELF`; 404 not a current member |
| POST | /api/conversations/{id}/leave | | 200 `Conversation` (as I see it now: `membership.active = false`) | 400 `NOT_A_GROUP`; 403 `NOT_A_MEMBER` |
| PUT | /api/conversations/{id}/mute | `{ duration: "EIGHT_HOURS" \| "ONE_WEEK" \| "ALWAYS" }` | 200 `Conversation` | 404 |
| DELETE | /api/conversations/{id}/mute | | 200 `Conversation` | 404 |
| PUT / DELETE | /api/conversations/{id}/archive | | 200 `Conversation` (archiving also unpins) | 404 |
| PUT / DELETE | /api/conversations/{id}/pin | | 200 `Conversation` (pinning also unarchives) | 404; 409 `PIN_LIMIT_REACHED` (at most 3 pinned chats) |

Common group errors: group endpoints on a PRIVATE conversation give 400 `NOT_A_GROUP`; an id I was never in gives 404; a group I left gives 403 `NOT_A_MEMBER` on the management endpoints. Mute / archive / pin work on every chat I am or was in.

`GROUP_ADD_NOT_ALLOWED` answer: `{ "success": false, "message": "...", "data": { "errorCode": "GROUP_ADD_NOT_ALLOWED", "userIds": ["..."] } }`. Nothing is created or added; the client can drop those people and try again. A user cannot be added when they do not exist, when either of us blocked the other, or when their `groupAdd` privacy setting refuses me (`CONTACTS`: only people in their contact list; `NOBODY`: nobody). The three reasons give the same answer on purpose.

### 4.3 Group rules
- The creator is ADMIN. Admins add and remove members, make or dismiss admins, and change the settings. A group has at most 256 members (`chatapp.groups.max-members`).
- When the last admin leaves, the member who joined earliest becomes admin automatically.
- `onlyAdminsCanSend` (default false): only admins may send messages. `onlyAdminsCanEdit` (default false): only admins may change the name and description.
- **History visibility (removed-member rule):** a member sees the messages created while they were a member: from `joinedAt` to `leftAt` (both inclusive). New members do not see older messages. A member who left or was removed keeps their old messages but sees nothing sent after they left. If they are added again, they see their earlier periods plus everything from the new join (one row per period, see DATABASE_DESIGN.md).
- **Blocking does not apply inside groups** (as in WhatsApp): two people who blocked each other can be in the same group and see each other's group messages. Blocking does stop either of them from adding the other to a group.
- Every group change (created, members added, removed, left, admin changes, name, description, settings) appears in the group as a SYSTEM message (section 5).

## 5. Messages (message-service)

### Phase 3: text messages, history, receipts, sync

| Method | Path | Request | Success | Errors (status: errorCode) |
|---|---|---|---|---|
| POST | /api/messages | `{ conversationId, type: "TEXT", content (1-4000 chars, not blank), idempotencyKey }` | 201 `Message` (status `SENT`); 200 with the **same** message when the `idempotencyKey` was already used by this user | 400 validation (incl. `type` other than TEXT); 404 conversation unknown or not yours; 403 `MESSAGING_NOT_ALLOWED` if either user blocked the other; 503 `DEPENDENCY_UNAVAILABLE` |
| GET | /api/conversations/{id}/messages?limit=30&before=C | `limit` 1-100 (default 30) | 200 `Page`: older messages than cursor `C` (omit `before` for the newest page), `items` **newest first** | 404 not a participant |
| GET | /api/conversations/{id}/messages?after=C&limit=100 | | 200 `Page`: messages newer than `C`, `items` **oldest first**, `hasMore` = more newer ones exist (continue with `nextCursor`) | 404 |
| POST | /api/messages/delivered | `{ messageIds: [uuid] }` (1-100) | 200 `{ updated: n }` | |
| POST | /api/conversations/{id}/read | `{ upToMessageId }` | 200 `{ updated: n }` | 404 not a participant / unknown message |
| GET | /api/messages/summaries | | 200 `[ { conversationId, unreadCount, lastMessage (Message or null) } ]` for every conversation in which I have messages | |
| GET | /api/messages/sync?after=C&limit=100 | `after` optional | 200 `Page` (oldest first) of messages newer than `C` across all my conversations, including my own sent from other devices. Without `after` the answer is empty and `nextCursor` is the current head: start from there | |

Shapes:
- `Message` = `{ id, conversationId, senderId, type: "TEXT", content, createdAt (UTC, microseconds), status, myReceipt }`
  - `status`: only for messages **I sent**: `SENT` | `DELIVERED` | `READ` (otherwise `null`). `READ` is hidden (shown as `DELIVERED`) when either user turned read receipts off.
  - `myReceipt`: only for messages **sent to me**: `null` | `DELIVERED` | `READ` (what my devices already acknowledged).
- `Page` = `{ items: [Message], nextCursor (string or null), hasMore }`. Cursors are opaque strings.

Rules:
- A message is saved before the 201 answer. `createdAt` is set by the server. Order is `(createdAt, id)`.
- `SENT` = saved. `DELIVERED` = a recipient device acknowledged it (`/api/messages/delivered` or STOMP `/app/chat.delivered`). `READ` = a recipient opened the conversation (`/read` or `/app/chat.read`). Nothing becomes delivered or read automatically; receipts only move forward.
- Only recipients can acknowledge (not the sender), and only for conversations they belong to.
- Sync can return a message the client already has (the server looks back 10 seconds so a slow transaction cannot be missed). Clients must de-duplicate by `id`.

### Phase 4: groups

Changes to the endpoints above:
- `Message.type` is `"TEXT"` or `"SYSTEM"`. Clients can only send `TEXT`. SYSTEM messages are written by the server for group events (below); they have `status: null` and `myReceipt: null`, never count as unread, and are never acknowledged (delivered / read ignore them).
- **Sending** needs a **current** membership (a former member gets the same 404 as a stranger). In a group with `onlyAdminsCanSend`, a member who is not admin gets 403 `ONLY_ADMINS_CAN_SEND`. The block rule (403 `MESSAGING_NOT_ALLOWED`) applies to PRIVATE chats only.
- **Reading** (history, sync, summaries, read, delivered, receipts) is allowed for current and former members, but only for messages inside the reader's membership periods `[joinedAt, leftAt]` (see 4.3). Messages outside are treated as if they did not exist (404 for `upToMessageId` / receipts, simply missing from pages).
- `summaries` covers every chat I have a membership period in (also groups I left). `lastMessage` can be a SYSTEM message.
- **Group message status** (for the sender): `SENT` until **every** recipient delivered it, `DELIVERED` when all did, `READ` when all read it. Recipients = the members who were in the group when the message was sent **and are still members** (a member who leaves no longer holds the status back; a member who joins later is not a recipient). In groups READ is always shown: the read-receipt privacy switch applies to private chats only (as in WhatsApp).

New endpoint:

| Method | Path | Success | Errors |
|---|---|---|---|
| GET | /api/messages/{id}/receipts | 200 `[ { userId, status: "SENT" \| "DELIVERED" \| "READ", deliveredAt, readAt } ]`, one entry per recipient (as defined above; private chat: the other person). `SENT` = not delivered yet. Times are the earliest over that user's devices. In a private chat a hidden read receipt shows as `DELIVERED` with `readAt: null` | 404 unless I am the sender (and the message is visible to me) |

**SYSTEM message content.** `content` is a JSON **string** (parse it on the client):
```
{ "event": "GROUP_CREATED",       "actor": User, "name": "Trip to Goa" }
{ "event": "MEMBERS_ADDED",       "actor": User, "users": [User] }
{ "event": "MEMBER_REMOVED",      "actor": User, "users": [User] }
{ "event": "MEMBER_LEFT",         "actor": User }
{ "event": "ADMIN_ADDED",         "actor": User | null, "users": [User] }   // actor null: automatic, the last admin left
{ "event": "ADMIN_REMOVED",       "actor": User, "users": [User] }
{ "event": "NAME_CHANGED",        "actor": User, "name": "New name" }
{ "event": "DESCRIPTION_CHANGED", "actor": User }
{ "event": "SETTINGS_CHANGED",    "actor": User, "setting": "ONLY_ADMINS_CAN_SEND" | "ONLY_ADMINS_CAN_EDIT", "value": true }
User = { "id": uuid, "name": string | null }   // the display name at the time of the change (null if it was unknown)
```
`senderId` of a SYSTEM message is the actor (for an automatic `ADMIN_ADDED`: the new admin). Clients should show the current name from `participants` when they have it, then the stored name, then "Someone"; and "You" for themselves.

### Phase 5: attachments (images, video, audio, voice notes, documents)

A media message is sent in two steps: **1.** upload the file to media-service (`POST /api/media/upload`, section 6) and get a `mediaId`; **2.** send the message with that `attachmentId`. The file itself never passes through message-service or the database.

Changes to `POST /api/messages`:
- `type` may now be `TEXT`, `IMAGE`, `VIDEO`, `AUDIO`, `VOICE` or `DOCUMENT` (`SYSTEM` is still server-only; `LOCATION` and `CONTACT` are not supported yet).
- `attachmentId` (uuid): **required** for every type except `TEXT`, and **not allowed** for `TEXT` (400 `VALIDATION`).
- `content` is the **caption** for media types: optional (omit it or send an empty string), at most 4000 characters. For `TEXT` it is still required (1-4000 characters, not blank).
- The attachment must have been uploaded by the sender (otherwise 404 `ATTACHMENT_NOT_FOUND`, the same answer for an unknown id and someone else's file), its kind must equal `type` (400 `ATTACHMENT_TYPE_MISMATCH`) and it must not be used by another message (409 `ATTACHMENT_ALREADY_USED`). Sending the same message again with the same `idempotencyKey` is fine and returns the same message. media-service unreachable: 503 `DEPENDENCY_UNAVAILABLE`.
- All other rules (membership, blocks, admins-only groups, receipts, removed-member rule) are unchanged and apply to media messages exactly like to text.

`Message` gets one more field: `attachment` = `null` (text and SYSTEM messages) or
`{ mediaId, kind: "IMAGE" | "VIDEO" | "AUDIO" | "VOICE" | "DOCUMENT", fileName, mimeType, sizeBytes, width, height, durationSeconds, hasThumbnail }`
(`width` / `height` only for images, `durationSeconds` only when the uploader sent it for audio / voice / video, otherwise `null`). It is part of every message the server returns or pushes (history, sync, summaries, MESSAGE_CREATED, MESSAGE_ACK).

New endpoint:

| Method | Path | Success | Errors |
|---|---|---|---|
| GET | /api/messages/{messageId}/attachment/url?variant=original | 200 `{ url, expiresAt }`: a **signed link straight to the object storage**, valid for 5 minutes. `variant=thumbnail` returns the small preview (only images with `hasThumbnail`). Documents are served with `Content-Disposition: attachment` (download), media inline | 404 if the message does not exist, is not visible to me (removed-member rule), has no attachment, or the thumbnail does not exist |

Browsers use the link directly in `<img>`, `<video>`, `<audio>` or a download link. Ask for a fresh link when `expiresAt` has passed. Anybody who may see the message may get a link; nobody else can (the access rule is the message's, not the file's).

Later phases (not built yet): edit, delete, reply, forward, reactions, pins, search, location and contact messages.

## 6. Media (media-service)

All calls need a bearer token. The uploader is the token's `sub`.

| Method | Path | Request | Success | Errors (status: errorCode) |
|---|---|---|---|---|
| POST | /api/media/upload | `multipart/form-data`: `file` (required), `purpose` = `IMAGE` \| `VIDEO` \| `DOCUMENT` \| `AUDIO` \| `VOICE` (required), `durationSeconds` (optional integer 0-7200: audio / voice / video length measured by the client) | 201 `Media` | 400 `VALIDATION` (no file, empty file, bad purpose); 413 `FILE_TOO_LARGE`; 415 `UNSUPPORTED_TYPE` (extension or type not allowed for this purpose); 415 `CONTENT_MISMATCH` (the file's real content does not match what it claims to be); 400 `INVALID_IMAGE` (not a decodable image or absurd dimensions) |
| GET | /api/media/{mediaId} | | 200 `{ media: Media, url, thumbnailUrl (or null), expiresAt }` signed links valid 5 minutes | 404 unless I am the uploader (used to preview my own upload before sending) |
| DELETE | /api/media/{mediaId} | | 200 `null` | 404 unless I am the uploader; 409 `MEDIA_IN_USE` once a message uses it |

`Media` = `{ id, kind, fileName, mimeType, sizeBytes, width, height, durationSeconds, hasThumbnail, createdAt }`.

**What is accepted (checked on the file's real bytes, not on the name or the browser's Content-Type):**

| Purpose | Types | Limit (configurable) |
|---|---|---|
| IMAGE | JPEG, PNG, GIF, WebP | 10 MB, at most 12000 pixels per side and 50 million pixels in total |
| VIDEO | MP4, QuickTime (MOV), WebM | 100 MB |
| AUDIO | MP3, M4A (MP4 audio), OGG / Opus, WAV, WebM audio | 25 MB |
| VOICE | the recording formats: WebM, OGG, MP4 audio, MP3, WAV | 25 MB |
| DOCUMENT | PDF, plain text (.txt), CSV, DOCX, XLSX, PPTX | 25 MB |

Rejected whatever the name says: executables, scripts, HTML, SVG, archives, and any file whose content does not match its claimed type. The stored `mimeType` is the **detected** one. File names are cleaned (no path, no control characters, at most 150 characters). Uploads that are never attached to a message are deleted after 24 hours. Image thumbnails (JPEG, at most 320 px) are made for JPEG, PNG and GIF; WebP images and videos have none (`hasThumbnail: false`).
Malware scanning is a replaceable component (`VirusScanner`). Without `VIRUS_SCANNER=clamav` nothing is scanned (development); in production every upload is scanned by ClamAV before it is stored: an infected file gives 400 `MALWARE_DETECTED`, and if the scanner is down the upload is refused with 503 `DEPENDENCY_UNAVAILABLE` (see SECURITY_DESIGN.md 7i).

## 7. Presence, Notifications, Calls

### 7.1 Presence (presence-service, Redis only; Phase 6)

All calls need a bearer token. The user is the token's `sub`; the **device** is the token's `sid` (one login session = one device), so one user can be online on several devices at once.

| Method | Path | Request | Success | Notes |
|---|---|---|---|---|
| POST | /api/presence/heartbeat | none | 200 `null` | "This device is alive." The app calls it every **20 seconds** while it is open (and once right after login). A device counts as online for **60 seconds** after its last heartbeat, so a crashed tab or a lost connection turns offline by itself |
| POST | /api/presence/offline | none | 200 `null` | "This device is gone now": call it on logout and when the page closes (use `fetch` with `keepalive: true`: `sendBeacon` cannot send the Authorization header). The user stays online if another device is still alive |
| GET | /api/presence/{userId} | | 200 `Presence` | see below |

`Presence` = `{ userId, visible, online, lastSeenAt }`
- `online`: true when at least one device of the user is alive. `lastSeenAt`: the time of the user's last heartbeat / offline call (UTC), or `null` if never seen. When `online` is true `lastSeenAt` is about now.
- **Privacy.** When the viewer may not see the status: `visible: false`, `online: null`, `lastSeenAt: null` (the client shows nothing). The rule, asked from user-service: you may always see yourself; nobody sees a user who blocked them (or whom they blocked); the target's "last seen" setting `EVERYONE` -> visible, `CONTACTS` -> visible only if the target has the viewer as a contact, `NOBODY` -> hidden. **Reciprocity** (as in WhatsApp): if the viewer's own setting is `NOBODY`, the viewer cannot see anybody's status either.
- A user who was never seen: `visible: true, online: false, lastSeenAt: null`.
- Typing indicators are not part of this service (they stay in message-service).

### 7.2 Notifications (notification-service, MySQL; Phase 6)

The service reads Kafka events (new messages, group changes) and keeps a **notification history** per user. Message text is never stored or sent: a message notification says who wrote and how many new messages there are. Rules for **new messages**: a notification is created only for recipients who are **offline** (no live device, asked from presence-service: online users see the message in the app), whose chat is **not muted**, and whose preferences allow it. Several unread messages of one chat become **one** notification whose `count` grows. When the user reads the chat (`MessageReadEvent`) that notification is marked read. **Group notifications** (added to a group, removed from a group, made admin) are created whether or not the user is online, if `groupsEnabled`. Push (to registered device tokens) is only sent to users who are offline.

| Method | Path | Request | Success | Notes |
|---|---|---|---|---|
| GET | /api/notifications?page=0&size=20 | `size` 1-50 | 200 `{ items: [Notification], page, size, totalItems, hasMore }` newest first (by `updatedAt`) | only my own |
| GET | /api/notifications/unread-count | | 200 `{ unreadCount }` | |
| PUT | /api/notifications/{id}/read | | 200 `Notification` | 404 if it is not mine |
| PUT | /api/notifications/read-all | | 200 `{ updated }` | |
| GET | /api/notifications/preferences | | 200 `Preferences` | defaults: everything `true` |
| PUT | /api/notifications/preferences | `Preferences` (all four fields required) | 200 `Preferences` | |
| POST | /api/notifications/device-tokens | `{ token (10-4096 chars), platform: "WEB" \| "ANDROID" \| "IOS" }` | 200 `null` | registers a push token for this user (the same token moves to the newest user) |
| DELETE | /api/notifications/device-tokens?token=... | | 200 `null` | removes my token (call it on logout) |

- `Notification` = `{ id, type: "NEW_MESSAGES" \| "GROUP_ADDED" \| "GROUP_REMOVED" \| "GROUP_ADMIN" \| "MISSED_CALL", title, body, conversationId (or null), count, read, createdAt, updatedAt }`. `MISSED_CALL` (Phase 7): title = the caller's name, body `Missed voice call` / `Missed video call`, `conversationId` null, `count` 1 (missed calls are not aggregated); created for online and offline callees alike, if `callsEnabled`. Examples: NEW_MESSAGES: title `Alice` (or the group name), body `3 new messages`, count 3. GROUP_ADDED: title = group name, body `Alice added you`.
- `Preferences` = `{ pushEnabled, messagesEnabled, groupsEnabled, callsEnabled }` (booleans; `callsEnabled` is used from Phase 7). `messagesEnabled` covers private chats, `groupsEnabled` covers everything about groups (messages and group changes).
- **Live:** whenever a notification is created or its count grows, the service pushes `{ type: "NOTIFICATION", data: Notification }` to `/user/queue/notifications` of that user (WEBSOCKET_DESIGN.md), so the bell updates without polling. The client also reads `unread-count` after (re)connecting.
- **Push:** `PushSender` is a replaceable component. The default one only writes a log line (counts, never content); a real FCM sender needs Firebase credentials and is **not** included. Failed pushes are retried 3 times with growing pauses, then marked dead (`notification_delivery_logs`).

### 7.3 Calls (call-signaling-service, MySQL; Phase 7)

One-to-one audio and video calls. Audio and video flow **directly between the two browsers** (WebRTC, peer to peer; through the TURN server when a direct path is impossible). The server only keeps the call's state and relays the connection set-up messages ("signaling"). All calls need a bearer token: the user is the token's `sub`, the **device** is the token's `sid`. One user can be in at most one call at a time.

**Shapes**
```
Call = {
  id, type: "AUDIO" | "VIDEO",
  status: "RINGING" | "ACCEPTED" | "ENDED" | "MISSED" | "REJECTED" | "BUSY",
  endReason: null | "HANGUP" | "CONNECTION_LOST" | "NO_ANSWER" | "CANCELLED" | "DECLINED" | "BUSY",
  direction: "OUTGOING" | "INCOMING",          // from MY point of view
  callerId, calleeId, otherUser: PublicProfile,  // otherUser = the other person, privacy applied for me
  createdAt, answeredAt (or null), endedAt (or null), durationSeconds   // duration = endedAt - answeredAt, else 0
}
CallSession = { call: Call, iceServers: [ { urls: [string], username?, credential? } ] }   // pass iceServers to RTCPeerConnection
```
- Status flow: `RINGING` -> `ACCEPTED` -> `ENDED` (`HANGUP` or `CONNECTION_LOST`). From `RINGING`: `REJECTED` (`DECLINED`, the callee said no), `MISSED` (`NO_ANSWER` after 30 s, or `CANCELLED` when the caller hung up first). `BUSY`: the callee was already in a call; it is final at once (nothing rings). Final states never change again.
- `iceServers`: a STUN server and the TURN server (Coturn) with **short-lived credentials** made for this user (HMAC of the shared `TURN_SECRET`, valid 8 hours by default). Only returned to the two participants.

| Method | Path | Request | Success | Errors (status: errorCode) |
|---|---|---|---|---|
| POST | /api/calls | `{ calleeId, type }` | 201 `CallSession` with status `RINGING` (this device is the caller's call device), or 201 with status `BUSY` and `iceServers: []` | 400 calling yourself; 404 unknown callee; 403 `CALL_NOT_ALLOWED` (either user blocked the other); 409 `ALREADY_IN_CALL` (I am already in a call, on any device) |
| GET | /api/calls/active | | 200 `Call` (my call that is `RINGING` or `ACCEPTED`) or 200 `null` | |
| GET | /api/calls/history?page=0&size=20 | `size` 1-50 | 200 `{ items: [Call], page, size, totalItems, hasMore }` newest first, every call I made or received | |
| GET | /api/calls/{id} | | 200 `Call` | 404 unless I am the caller or the callee |
| POST | /api/calls/{id}/accept | | 200 `CallSession` (status `ACCEPTED`; this device becomes the callee's call device) | 404; 403 `NOT_CALLEE`; 409 `CALL_NOT_RINGING` (already answered elsewhere, ended, missed...) |
| POST | /api/calls/{id}/reject | | 200 `Call` (`REJECTED` / `DECLINED`) | 404; 403 `NOT_CALLEE`; 409 `CALL_NOT_RINGING` |
| POST | /api/calls/{id}/end | | 200 `Call`. RINGING + caller -> `MISSED` / `CANCELLED`; RINGING + callee -> `REJECTED` / `DECLINED`; ACCEPTED -> `ENDED` / `HANGUP`. Already final: 200 with the call unchanged (safe to repeat) | 404 |
| POST | /api/calls/{id}/signal | `{ type: "OFFER" \| "ANSWER" \| "ICE", sdp?, candidates? }` | 200 `null`: relayed live to the other participant's call device | 404; 409 `CALL_NOT_ACTIVE` (not `ACCEPTED`); 403 `NOT_CALL_DEVICE` (sent from another device of mine); 400 validation |
| POST | /api/calls/{id}/ping | | 200 `null`: "my side of the call is still alive" | 404; 409 `CALL_NOT_ACTIVE`; 403 `NOT_CALL_DEVICE` |

**Signal body rules:** `OFFER` / `ANSWER` need `sdp` (1-20000 characters) and no `candidates`; `ICE` needs `candidates` (1-50 items, each `{ candidate (0-1024 characters), sdpMid (max 64, or null), sdpMLineIndex (0-64, or null) }`). Signals are relayed, never stored.

**Rules**
- Calling needs only that neither user blocked the other (as for private messages); no contact or existing chat is required. Group calls are not supported.
- **Busy:** each user holds at most one call (a database row per user taken in the same transaction that creates the call). If the callee is busy the call is saved as `BUSY` and the caller is told at once; if I am busy myself I get 409 `ALREADY_IN_CALL`. Two people calling each other at the same moment: one call wins; the other caller gets `BUSY` or 409 `ALREADY_IN_CALL` (they are already ringing in the winning call).
- **Devices:** the callee's call **rings on every device** of the callee; the first device that accepts gets the call, and the others stop ringing (they receive `CALL_ACCEPTED` for a call they did not accept). Signals and pings are only accepted from the two call devices (caller's device = the one that created the call).
- **Order of the set-up:** after `CALL_ACCEPTED` the **caller's** device creates the WebRTC offer and sends `OFFER`; the callee answers with `ANSWER`; both send `ICE` candidates as they find them (in small batches). A client must keep ICE candidates that arrive before the offer/answer and add them afterwards.
- **Time limits:** a call that is still `RINGING` after **30 s** becomes `MISSED` / `NO_ANSWER`. During an `ACCEPTED` call both call devices `ping` every **10 s**; when one side has not pinged for **30 s** the server ends the call (`ENDED` / `CONNECTION_LOST`). All three values are configurable (`chatapp.calls.*`).
- **After a page reload** the old WebRTC connection is gone: the app asks `GET /api/calls/active`. The browser tab remembers the id of the call it was in (`sessionStorage`); if that is the active call (`ACCEPTED`, or my own outgoing `RINGING` call) the tab ends it with `/end`; an incoming `RINGING` call is shown as ringing. A call no tab remembers (storage lost) ends by itself through the ping time-out. (Two tabs of one browser share one login session, so the device id alone cannot tell them apart.)
- Every finished call is published to Kafka as `CallEndedEvent`. notification-service turns a **missed** call (`MISSED`, or `BUSY`: the callee could not take it) into a `MISSED_CALL` notification for the callee (if `callsEnabled`).
- **Live events** arrive on the chat WebSocket, `/user/queue/calls` (WEBSOCKET_DESIGN.md section 9).

### 7.4 Status / stories (status-service, MySQL; Phase 8)

Short updates (text on a coloured background, a photo or a video with an optional caption) that disappear **24 hours** after they were posted. All calls need a bearer token; the user is the token's `sub`.

**Shapes**
```
Status = {
  id, ownerId, type: "TEXT" | "IMAGE" | "VIDEO",
  text (TEXT only, else null), backgroundColor (TEXT only, else null), caption (IMAGE / VIDEO, or null),
  media: null | { kind: "IMAGE" | "VIDEO", mimeType, width, height, durationSeconds, hasThumbnail },
  createdAt, expiresAt,             // expiresAt = createdAt + 24 h
  viewedByMe: boolean               // always true for my own statuses
}
MyStatus    = Status + { viewCount }                       // viewers I am allowed to see (rules below)
StatusGroup = { owner: PublicProfile, statuses: [Status] (oldest first), latestAt, allViewed }
Viewers     = { viewersHidden: boolean, viewCount, viewers: [ { user: PublicProfile, viewedAt } ] (newest first) }
StatusPrivacy = { mode: "CONTACTS" | "CONTACTS_EXCEPT" | "ONLY_SHARE_WITH", userIds: [uuid] }
```
- `backgroundColor` is one of `#0F766E`, `#1D4ED8`, `#7C3AED`, `#BE185D`, `#B91C1C`, `#C2410C`, `#4D7C0F`, `#334155` (white text is readable on all of them).

| Method | Path | Request | Success | Errors (status: errorCode) |
|---|---|---|---|---|
| POST | /api/statuses | TEXT: `{ type: "TEXT", text (1-700, not blank), backgroundColor, idempotencyKey }`; IMAGE / VIDEO: `{ type, mediaId, caption? (max 700), idempotencyKey }` | 201 `Status`; 200 with the same status when this `idempotencyKey` was already used by me | 400 validation (incl. a colour outside the list); 404 `ATTACHMENT_NOT_FOUND` (not my upload / unknown / deleted); 400 `ATTACHMENT_TYPE_MISMATCH` (an IMAGE status with a video upload...); 409 `ATTACHMENT_ALREADY_USED`; 409 `STATUS_LIMIT_REACHED` (30 active statuses); 503 `DEPENDENCY_UNAVAILABLE` |
| GET | /api/statuses/mine | | 200 `[MyStatus]` my active statuses, oldest first | |
| GET | /api/statuses/feed | | 200 `[StatusGroup]` everybody whose active statuses I may see (never me): groups with something I have not viewed first, then fully viewed groups; inside each part newest `latestAt` first | 503 |
| POST | /api/statuses/{id}/view | | 200 `null` "I saw it" (safe to repeat; my own status: nothing happens) | 404 if I may not see it or it expired |
| GET | /api/statuses/{id}/viewers | | 200 `Viewers` | 404 unless it is my own active status |
| GET | /api/statuses/{id}/media-url?variant=original\|thumbnail | | 200 `{ url, expiresAt }` signed link (5 minutes) | 404 if I may not see it, it expired, it has no media or no thumbnail |
| DELETE | /api/statuses/{id} | | 200 `null` (the media file is deleted too) | 404 unless it is my own status |
| GET | /api/statuses/privacy | | 200 `StatusPrivacy` (default `CONTACTS`, `[]`) | |
| PUT | /api/statuses/privacy | `StatusPrivacy` (`userIds` 0-500; must be empty for `CONTACTS`) | 200 `StatusPrivacy` | 400 validation |

**Who sees my status** (decided on every request, so a change applies at once, also to statuses already posted):
- never someone who blocked me or whom I blocked;
- `CONTACTS`: the people in **my** contact list;
- `CONTACTS_EXCEPT`: my contacts except `userIds`;
- `ONLY_SHARE_WITH`: only those of `userIds` who are also in my contact list.
I always see my own statuses (`/mine`). An expired status is gone for everybody (404).

**Viewers** (as in WhatsApp, read receipts decide): a view is always recorded (so the viewer's own ring turns grey), but it is only **listed and counted** for the owner when the **viewer** has read receipts on; and if the **owner** turned read receipts off, `viewersHidden` is true, `viewCount` 0 and the list empty.

**Photos and videos:** upload first (`POST /api/media/upload`, purpose `IMAGE` or `VIDEO`, as for messages), then post the status with the `mediaId`. Media is shown through short-lived signed links from `/media-url` (thumbnails exist for photos only). Text and captions are plain text.

**Expiry:** a status is visible until `expiresAt`; a job then deletes the status, its views and its media file. Nothing is kept. No Kafka event is published (nobody would consume `StatusPosted` yet), and there are no notifications for new statuses: the Status page asks for the feed when it opens and every 30 seconds while it is open.

## 8. Internal endpoints (not routed by the gateway)
Only other services call these, inside the Docker network. Every request must carry the header `X-Internal-Token: <INTERNAL_SERVICE_TOKEN>` (an environment variable, same value in all services); a missing or wrong token gives 401. They are **not** protected by a user JWT and are never routed by the gateway. Responses use the normal `ApiResponseDTO` envelope.

| Service | Endpoint | Answer (`data`) |
|---|---|---|
| user-service | `GET /internal/blocks?a={uuid}&b={uuid}` | `{ blocked: boolean }` true when **either** user blocked the other |
| user-service | `GET /internal/users/{id}/privacy` | `Privacy` (section 3); 404 if unknown |
| user-service | `POST /internal/users/summaries` body `{ viewerId, userIds: [uuid] }` | `[PublicProfile]` for the ids that exist, with privacy applied for `viewerId` |
| user-service | `POST /internal/users/group-add-check` body `{ actorId, userIds: [uuid] (1-256) }` | `{ notAllowed: [uuid] }`: the users the actor may NOT add to a group (unknown, blocked either way, or their `groupAdd` privacy refuses the actor). Empty list = all allowed |
| conversation-service | `GET /internal/conversations/{id}` | `{ id, type, participantIds: [uuid], adminIds: [uuid], onlyAdminsCanSend: boolean, members: [ { userId, joinedAt, leftAt } ] }`; 404 if unknown. `participantIds` = current members. `members` = **every membership period**, current and past (`leftAt` null = still a member; a user who was added again has several entries). PRIVATE: `adminIds` empty, `onlyAdminsCanSend` false |

| media-service | `POST /internal/media/{id}/attach` body `{ ownerId, kind, reference }` | `Media`. Marks the file as used by a message. 404 `ATTACHMENT_NOT_FOUND` (unknown, deleted, or not uploaded by `ownerId`), 400 `ATTACHMENT_TYPE_MISMATCH` (`kind` differs from the upload's kind), 409 `ATTACHMENT_ALREADY_USED` (already used with a **different** `reference`). The same `reference` again is fine (idempotent). `reference` = `<senderId>:<idempotencyKey>` |
| media-service | `POST /internal/media/{id}/download-link` body `{ requesterId, variant: "original" \| "thumbnail" }` | `{ url, expiresAt }` signed link (5 minutes). Only for files that are attached to a message; the **caller has already checked** that `requesterId` may see that message. 404 if unknown / not attached / thumbnail missing. Writes an access-log row |

| user-service | `POST /internal/users/presence-visibility` body `{ viewerId, targetId }` | `{ visible: boolean }`: may the viewer see the target's online status / last seen? Rules in 7.1 (blocks either way -> false; viewer's own `lastSeen` = NOBODY -> false; target's `lastSeen` EVERYONE / CONTACTS (target has the viewer as a contact) / NOBODY). An unknown target gives `false` |
| conversation-service | `GET /internal/conversations/{id}` (extended) | also `name`: the group name, `null` for private chats |
| conversation-service | `GET /internal/conversations/{id}/muted-users` | `{ mutedUserIds: [uuid] }`: current members whose mute is active now (`mutedUntil` in the future); 404 if unknown |
| presence-service | `GET /internal/presence/online?userIds=a,b,c` (1-200 ids) | `{ onlineUserIds: [uuid] }`: the ids that have a live device right now (no privacy applied: for server-side decisions only) |

| user-service | `GET /internal/users/{viewerId}/contact-owners` | `{ ownerIds: [uuid] }`: every user who has `viewerId` in **their** contact list, minus anybody blocked either way (Phase 8: whose statuses may this viewer possibly see?) |
| user-service | `GET /internal/contacts/relation?ownerId={uuid}&viewerId={uuid}` | `{ contact: boolean, blocked: boolean }`: is `viewerId` in `ownerId`'s contact list, and did either block the other (Phase 8: may this viewer see this owner's status?) |
| media-service | `POST /internal/media/{id}/release` body `{ ownerId, reference }` | `null`. Deletes an **attached** file (database row DELETED + objects removed) when `ownerId` uploaded it and `reference` is exactly the one it was attached with; already deleted: 200 (safe to repeat); otherwise 404. Used by status-service for expired or deleted statuses (reference `status:<ownerId>:<idempotencyKey>`); a message's file can never be released this way because its reference differs |

Callers use short timeouts (2 s connect, 3 s read) and turn failures into 503 `DEPENDENCY_UNAVAILABLE`. (Exception: nothing here is slow; uploads themselves go straight through the gateway to media-service and have their own, longer limits.)
