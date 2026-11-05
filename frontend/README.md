# chat-app frontend

React + TypeScript + Vite + Tailwind CSS v4. Talks only to the API gateway.

## Run in development
```bash
npm install
npm run dev
```
Open http://localhost:5210. The Vite dev server forwards `/api` and `/ws` to the gateway at http://localhost:8200, so start the backend (infrastructure, gateway, auth-service, user-service, conversation-service, message-service) first. `/status` shows "Connected" when the frontend can reach the backend. Verification and password-reset emails arrive in MailHog at http://localhost:8025.

Optional: copy `.env.example` to `.env` and set `VITE_API_BASE_URL` to call a gateway on another origin. Leave it empty normally.

## Scripts
| Command | What it does |
|---|---|
| `npm run dev` | dev server on port 5210 |
| `npm run build` | type-check (app, config and e2e files) and build into `dist/` |
| `npm test` | run unit tests once (Vitest + React Testing Library) |
| `npm run test:watch` | tests in watch mode |
| `npm run lint` | lint with oxlint |
| `npm run e2e` | Playwright end-to-end test against a running stack (see below) |

## Run in Docker
```bash
docker build -t chat-app-frontend .
docker run --rm -p 5210:8080 chat-app-frontend
```
The container serves the built app with nginx (the unprivileged image: runs as a normal user, port **8080**, version hidden) and sends the security headers of Phase 9 (Content-Security-Policy, nosniff, frame and referrer rules, Permissions-Policy; see `nginx.conf.template` and `security-headers.conf.template`; `S3_PUBLIC_ORIGIN` is the address browsers may load signed files from). It proxies `/api/` and `/ws/` to `http://api-gateway:8200`. That name only resolves inside the Docker Compose network, so run it through the root Compose file rather than alone.

## Pages
| Route | Access | What it does |
|---|---|---|
| `/login` | public | email or phone + password, "Remember me" |
| `/register` | public | create an account, then go to `/verify` |
| `/verify` | public | enter the 6-digit email code (also used for "OTP verification"), resend with a 60 s wait |
| `/forgot-password`, `/reset-password?token=` | public | reset password through the emailed link |
| `/system-status` | public | backend connection check (Phase 1; it used to be `/status`) |
| `/` | logged in | redirects to `/chats` |
| `/chats` | logged in | the chat list (phone) / chat list with an empty state on the right (desktop). Pinned chats first, an "Archived" folder, a menu per chat (pin, mute, archive) and "New group" |
| `/chats/:conversationId` | logged in | the open chat. Desktop: list on the left, chat on the right (plus the group info panel for groups). Phone: only the chat, with a back arrow; the group info panel is full screen |
| `/contacts` | logged in | my contacts (with "Start a conversation"), find people (search, add, block), blocked users, profile pop-up |
| `/profile` | logged in | view and edit display name and about |
| `/settings` | logged in | privacy, notifications (server switches + desktop pop-ups), theme, active devices, log out |
| `/notifications` | logged in | the full notification list (phones open it from the bell; big screens use the bell panel) |
| `/status` | logged in | status / stories: my status, "Recent updates", "Viewed updates", composers, story viewer (Phase 8) |
| `/calls` | logged in | call history (newest first, missed calls in red, duration or outcome, call back by voice or video, Load more) |

## How login and tokens work
- The **access token** (valid 15 minutes) lives only in memory (Zustand `authStore`). It is gone when the page reloads.
- The **refresh token** (valid 7 days) is stored by `services/api/tokenStorage.ts`: in `localStorage` if "Remember me" was ticked, otherwise in `sessionStorage`.
- On start-up, if a refresh token is stored, the app swaps it for a new access token before showing any logged-in page (loading screen meanwhile).
- When a call fails with 401, `services/api/apiClient.ts` refreshes the token and retries the call once. Refresh tokens are **single-use** and the server ends the session if one is used twice, so `services/api/refreshSession.ts` makes all parallel failed calls **share one refresh request**. Never call `/api/auth/refresh` from anywhere else (the WebSocket manager uses the same `refreshSession`).
- If the refresh is rejected, the session is cleared and `ProtectedRoute` sends the user to `/login`. A network problem does not log the user out.
- Logging out first tells the presence service the device is offline, then calls `/api/auth/logout`, wipes the stored token, the whole query cache and the chat data (so the next user never sees old data), and closes the WebSocket.

## Folder structure
```
src/
  components/common/   reusable pieces (Alert, FormField, PasswordInput, SubmitButton, Avatar, ThemeToggle, DropdownMenu, ConfirmDialog)
  components/chat/     ConversationList, ChatWindow, MessageList, MessageBubble, StatusTicks, Composer, ConnectionBanner,
                       ConversationAvatar, ChatOptionsMenu, ContactPicker, NewGroupDialog, AddMembersDialog,
                       GroupInfoPanel, MessageInfoDialog, AttachMenu, AttachmentPreviewTray, VoiceRecorderBar,
                       MessageAttachment (picture / video / audio / document in a bubble), ImageLightbox
  components/contacts/ contact list, search, blocked list, profile pop-up
  components/notifications/  NotificationBell (badge + panel), NotificationList
  components/calls/    CallOverlay (the call screen over every page), CallButtons (voice / video in a private chat header)
  features/authentication/  loginUser / logoutUser / restoreSession, friendly error messages
  features/messaging/  messageMerge (pure list rules), chatActions (load history, optimistic send, group and chat-settings actions),
                       conversationInfo (who may do what, titles, typing text), systemMessages (SYSTEM message sentences),
                       realtime (socket events + sync), typing, cursorStorage, conversationList helpers, useRealtime hook
  features/media/      mediaRules (what may be uploaded, sizes, messages), attachmentActions (send a file: upload + message,
                       cancel, retry), useVoiceRecorder (microphone), useAttachmentUrl (signed links), localPreviews
  features/presence/   usePresenceHeartbeat, usePresence, formatPresence (online / last seen)
  features/notifications/  notificationActions (live events, unread count, read), localNotifications (desktop pop-ups)
  features/calls/      callEngine (WebRTC + the call state machine), callStore (what the call screen shows), callRealtime
                       (/user/queue/calls, reconnect, page close), callText (sentences, durations), ringtone (Web Audio)
  hooks/               useCountdown, useDebouncedValue
  layouts/             MainLayout (public pages), AppLayout (logged-in pages, keeps the socket open)
  pages/               auth/, chat/, calls/, contacts/, profile/, settings/ and the status page
  routes/              route table and ProtectedRoute
  services/api/        axios client, token refresh, AppError, authApi, userApi, chatApi, mediaApi, presenceApi, notificationApi, callApi
  services/websocket/  the single STOMP connection (websocketManager) and useConnectionState
  services/queryClient.ts  the shared TanStack Query cache
  store/               Zustand stores (auth, theme, chat, notifications)
  types/               TypeScript types for API data
  utils/               small helpers
  validations/         zod form rules (mirror the backend rules)
  styles/              Tailwind entry css
  test/                test setup, helpers and fixtures
e2e/                   Playwright end-to-end tests (not part of `npm test`)
```

## How API errors work
Every failed call is converted to one `AppError` (`src/services/api/appError.ts`) with a `kind` (NETWORK, VALIDATION, UNAUTHORIZED, ...), a friendly `message`, `fieldErrors` for validation errors and an `errorCode` for business errors such as `EMAIL_TAKEN` or `MESSAGING_NOT_ALLOWED` (see `API_DOCUMENTATION.md`). `features/authentication/authErrors.ts` turns auth error codes into friendly sentences.

## Chat: how it works

### Data
All chat data lives in one Zustand store (`store/chatStore.ts`): conversations, messages per conversation, unread counts and last messages (`summaries`), paging state and typing. The chat list is `GET /api/conversations` merged with `GET /api/messages/summaries` by `conversationId`. TanStack Query is used for the contacts and settings pages; the chat uses the store because live events must update the very same data.

`features/messaging/messageMerge.ts` holds the pure rules (heavily tested):
- messages are sorted by `(createdAt, id)` with microsecond precision and **de-duplicated by id** (a message can arrive live, in a history page and in a sync);
- a status only moves forward `SENT -> DELIVERED -> READ`; a late or repeated update never goes back.

### Message list
A plain list (**react-virtuoso is not used**). Older messages are loaded one page of 30 at a time when you scroll to the top (`GET /api/conversations/{id}/messages?before=<cursor>`) and the scroll position is kept, so the list never holds the whole history. Virtualization was left out because it makes prepending and scroll-to-bottom much harder to get right, and the paging already keeps the DOM small. Rules: opening a chat jumps to the newest message; a new message follows you only if you are at the bottom (or it is yours), otherwise a "scroll to latest" button with a counter appears. The "N unread messages" divider is placed once, when the chat opens.

### WebSocket (`services/websocket/websocketManager.ts`)
- **One** connection (STOMP over WebSocket at `/ws`), opened by `useRealtime()` in the layout of all logged-in pages and closed on logout.
- The access token goes in the STOMP `CONNECT` header, never in the URL, and a fresh one is fetched before every (re)connect (same single-flight refresh as the REST client). The server closes sockets whose token expired; the client just reconnects.
- Reconnect: own exponential backoff 1 s, 2 s, 4 s ... max 30 s, each wait randomised between half and the full value. A banner "Reconnecting..." shows while the connection is down.
- Subscriptions are kept in a registry by destination: asking twice for the same destination gives one STOMP subscription, and after a reconnect each destination is subscribed again exactly once.
- Subscriptions (all personal, made once at start-up): `/user/queue/messages`, `/user/queue/receipts`, `/user/queue/acks`, `/user/queue/errors` and `/user/queue/typing` (plus `/user/queue/notifications` and, since Phase 7, `/user/queue/calls`, subscribed by their own features). Phase 4 removed the per-chat `/topic/conversation/{id}` subscription: the server now sends typing only to the current members, so someone removed from a group stops receiving it.
- Acknowledgements (delivered, read) go over REST, which is simpler and reports errors; sending messages also uses REST (`POST /api/messages`). The socket is for receiving and for typing.

### After every (re)connect (`features/messaging/realtime.ts`)
`GET /api/messages/sync?after=<lastCursor>` is called repeatedly while `hasMore`, the messages are merged (de-duplicated), the server is told which incoming messages were received (`POST /api/messages/delivered`, batched within 200 ms), and then the chat list and unread counts are reloaded. The cursor is saved per user in `localStorage`. The very first sync has no cursor and only returns the starting point.

### Receiving, delivered, read
A live message from someone else is added and a delivered acknowledgement is queued. Then either (chat open **and** tab visible) the conversation is marked read (`POST /api/conversations/{id}/read`), or the unread count goes up. When the tab becomes visible again, the open chat is marked read. Receipt events update the ticks of my messages: clock = sending, one tick = saved, two grey ticks = delivered, two blue ticks = read, red = failed.

### Sending (optimistic)
`features/messaging/chatActions.ts`: the message appears at once with status SENDING and a client-made `idempotencyKey` (`crypto.randomUUID()`), then `POST /api/messages` is sent. On success the pending copy is replaced by the server's. If the live event of the same message arrives first, the pending copy is recognised (same conversation, same sender, same text) and removed, so there is never a duplicate. On failure the message turns red with a **Retry** button. Retry sends the **same** `idempotencyKey`, so the server can never create a second message. A blocked conversation shows "You can't message this user." Failed messages are only kept in memory (a page reload loses them).

### Typing
`features/messaging/typing.ts`: while the user types, at most one "typing" frame every 2 s (`/app/chat.typing`), and one "stopped" frame after 3 s without typing, when the box is emptied, when the message is sent, or when the chat is left. The other side shows "typing..." (in a group: who is typing) and hides it by itself after 5 s if no "stopped" frame arrives. Typing is tracked per conversation **per person** (`chatStore.typing[conversationId]` is the list of people typing), each with its own 5 s expiry.

## Groups (Phase 4)
- **New group** (chat list header): step 1 picks people from my contacts (filter box, chips), step 2 asks for a name (1-100) and an optional description (max 500). If the server answers `GROUP_ADD_NOT_ALLOWED`, the refused people are marked "can't be added (privacy settings)" on step 1 and must be unticked before trying again.
- **Group chat window**: the header shows the group name and "You, Asha, Ravi" (or "Asha is typing...", "Asha and Ravi are typing...", "3 people are typing..."). Other people's messages show the sender's name (a stable colour per person) above the first message of each run. My messages have an "i" button for **Message info** (`GET /api/messages/{id}/receipts`: Read by / Delivered to / Waiting).
- **SYSTEM messages** ("Asha added Ravi", "You left") are centred notes. Their content is JSON; `features/messaging/systemMessages.ts` turns it into a sentence ("You" for me; a person's current name, else the name stored in the message, else "Someone"). They never count as unread and are never acknowledged. When one arrives live, the app reloads that conversation (`GET /api/conversations/{id}`), because members, roles, name or settings changed; for an unknown conversation (I was just added) it reloads the chat list.
- **Group info panel** (info button in the header): name and description (editable by admins, or by everybody when the group allows it), the members (admins first, "You"), and for admins: Add members, a menu per member (Make group admin / Dismiss as admin / Remove from group, with confirmation) and the switches "Only admins can send messages" / "Only admins can edit group info". "Exit group" asks first. Every action stores the conversation the server answers with, so every screen updates at once.
- **Composer rules**: a group I left or was removed from shows "You can't send messages to this group because you're no longer a participant." instead of the composer (the old messages stay readable); with "only admins can send" a member sees "Only admins can send messages."
- **My chat settings** (menu on every chat row and in the chat header, for private chats too): Pin / Unpin (at most 3, the server answers `PIN_LIMIT_REACHED`), Mute for 8 hours / 1 week / always / Unmute (muted chats show a bell icon and a grey unread badge), Archive / Unarchive (archived chats live in the "Archived (n)" folder at the top of the list).
- Group error codes get friendly sentences in `describeGroupError` (`features/messaging/chatActions.ts`). `AppError.details` carries the rest of the error's `data`, e.g. the `userIds` of `GROUP_ADD_NOT_ALLOWED`.

## Files and voice notes (Phase 5)
**Sending a file.** The paperclip (left of the box) opens a menu: Photo, Video, Document, Audio. A picked file gets a **preview tray** above the box (a thumbnail for pictures, a card with name and size for the rest) and the text box becomes its **caption**. Enter or the Send button sends it; the X removes it. Dropping a file anywhere on the chat window, or pasting a picture into the box, does the same.

Before anything is uploaded the file is checked against the server's rules (`features/media/mediaRules.ts`: image 10 MB, video 100 MB, audio / document 25 MB, allowed extensions and types). That only saves a useless upload: the server checks the real bytes again and is the authority.

**How the upload works** (`features/media/attachmentActions.ts`), two steps with one idempotency key:
1. The message appears at once as SENDING with the file from this computer (an object URL, `previewUrl`), a **progress bar** (`uploadProgress`, real progress from axios `onUploadProgress`) and a **Cancel** button. The file is sent to `POST /api/media/upload` as multipart (no 15 s timeout: big files take long; Cancel aborts it with an `AbortController`).
2. When the upload answers with a `mediaId`, `POST /api/messages` is sent with `type`, `attachmentId`, the caption and the idempotency key.

Failures: a **lost connection or busy server** keeps the bubble as FAILED with **Retry** and **Discard**. Retry uses the **same key** and, if the upload already worked, the **same `mediaId`**, so the file is never uploaded twice and the message never twice. If the server says the uploaded file is gone (`ATTACHMENT_NOT_FOUND`), the next Retry uploads it again. A file the server **refuses for good** (wrong or fake type, too large, unreadable image: 413 / 415 / `CONTENT_MISMATCH` / `INVALID_IMAGE`) removes the bubble and shows the reason in the composer, because trying again cannot work. The files and abort switches live in a module-level map, not in the store (the store only holds plain data).

**Voice notes.** The microphone button (shown when the box is empty) starts `MediaRecorder` (`audio/webm;codecs=opus`, else `audio/mp4` or `audio/ogg`, whatever the browser supports). While recording the box is replaced by a bar with a pulsing dot, the time, **Cancel** (discards) and **Send**. At least 1 second, at most 5 minutes (it stops and sends by itself). The microphone is always released (when recording ends, is cancelled, or the chat closes). The duration is measured in the browser and sent as `durationSeconds`; the file is uploaded as `voice-note.<ext>` with purpose `VOICE`, then follows the normal path above. A denied permission, no microphone, or an unsupported browser shows a friendly message (`useVoiceRecorder.ts`).

**Showing files** (`MessageAttachment.tsx`). A saved message asks the server for a **signed link** when it is drawn (`GET /api/messages/{id}/attachment/url`, via `useAttachmentUrl`, cached by TanStack Query for a little less than the 5 minutes the link lives, never written to localStorage). Pictures show the small thumbnail (the original when there is none) in a box with the picture's shape, so the chat does not jump; a click opens the original in a lightbox (Esc closes, focus is handled). Videos use a `<video controls preload="metadata">`, audio and voice notes an `<audio controls>` with the duration, documents a card whose Download button asks for a **fresh** link at the moment of the click (the storage sends it as an attachment). If the browser cannot load a file (for example the link expired while the page was open), the hook fetches a new link once. While a link loads there is a grey placeholder; if it cannot be fetched there is an error with Retry. For the sender, the local picture / player keeps showing after the message was saved (`localPreviews.ts`), so nothing flickers.

The chat list shows an icon and a label for media messages ("Photo", "Video", "Audio", "Voice message", "Document") plus the caption.

## Presence and notifications (Phase 6)

### Online / last seen
- **Heartbeat** (`features/presence/usePresenceHeartbeat.ts`, started in `AppLayout`): `POST /api/presence/heartbeat` right after login and then every **20 s**, but only while the tab is visible. When a hidden tab becomes visible again one heartbeat is sent at once. A failed heartbeat is ignored (the next one tries again). Nothing is sent while logged out. The server treats a device as online for 60 s after its last heartbeat.
- **Going offline**: `POST /api/presence/offline` is sent when the user logs out (**before** the tokens are cleared, because the call needs the access token) and when the page is closed (`pagehide`). It uses `fetch(..., { keepalive: true })` because `navigator.sendBeacon` cannot send the `Authorization` header. Errors are swallowed: if it is lost, the 60 s timeout does the same job.
- **In the private chat header** (`PresenceStatus`, `usePresence`): `GET /api/presence/{otherUserId}` when the chat opens and then every 10 s while the tab is visible (and when it becomes visible or the socket reconnects). The old answer stays on screen while a new one loads, so the header does not flicker. It shows a green dot with "online", or "last seen today at 10:32" / "yesterday at 21:05" / "03 Nov at 18:20" (with the year when it is older than a year). It shows **nothing** when the other person hides their status (`visible: false`) or was never seen. "typing..." replaces it while the other person types. Groups show the member names as before. `formatPresence.ts` holds the pure formatting rules (a time slightly in the future because of clock differences is shown as "now").

### Notification panel (redesigned in Phase 11)
- **Placement:** the panel is rendered through a portal into `<body>` (`NotificationBell.tsx`) and positioned from the bell's screen position, so no parent with `overflow: hidden` can cut it off (that was the first bug). Big screen: opens to the right of the bell; between 640 and 767 px below it; a phone opens the `/notifications` page instead.
- **One state at a time** (`NotificationList.tsx`): loading skeleton, error, "Notifications are turned off" (all of messages, groups and calls off, with "Turn them on"), "You are all caught up", or the list ("New" above "Earlier", one line of text each). The header shows the unread count, "Mark all as read" (only when there is something) and a settings shortcut.
- **A click always goes somewhere** (`notificationPath` in `notificationActions.ts`): the chat, `/calls` for a missed call, `/chats` when the notification has no chat. It also marks the entry read and closes the panel.
- **The switches are stored once** (`useNotificationStore().preferences`, loaded after login by `startNotifications`, updated by Settings). The panel, Settings and the desktop pop-ups (`allowedBySwitches` in `localNotifications.ts`) all read it: "Push notifications" is the master switch for pop-ups, "New messages in private chats" and "Groups" decide per kind of chat. The switches themselves are `components/common/Toggle.tsx` (a real checkbox drawn as a switch).

### Notification bell
- The bell is in the top bar of every logged-in page. Its **unread badge** shows the number (`99+` above 99). The number is read with `GET /api/notifications/unread-count` after login and after **every** WebSocket (re)connect, and moves live from `NOTIFICATION` events.
- `store/notificationStore.ts` keeps the badge number and the list. A live notification with an id we already have **replaces** it (the server aggregates new messages of one chat into one notification, "5 new messages", whose count grows in place), so the same chat never appears twice. `features/notifications/notificationActions.ts` subscribes to `/user/queue/notifications` (through the same WebSocket manager registry as the chat queues), applies events, and asks the server for the true number shortly after a burst of events (one request, 500 ms debounce).
- **The panel** (`NotificationList`): newest first, 20 per page with **Load more**, an icon per type (new messages, added to / removed from a group, made admin), title, text, relative time ("5 min ago", "yesterday") and a dot for unread ones. Clicking an entry marks it read (the badge drops at once, then `PUT /api/notifications/{id}/read`) and opens `/chats/:id`; "Mark all as read" does `PUT /api/notifications/read-all`. On a big screen the bell opens a panel under it; on a phone it opens the full page `/notifications` (also a normal protected route).
- Opening a chat marks its messages read on the server, which also marks the chat's notification read; so the unread number is read again 3 s and 8 s after a chat opens.

### Settings > Notifications
Four switches saved with `PUT /api/notifications/preferences` (**optimistic**: the switch moves at once and goes back with a message if the server refuses): Push notifications, New messages in private chats, Groups, Calls ("Available with calls", it has no effect yet). Below them, **Desktop notifications on this device** asks the browser for permission (`Notification.requestPermission()`), and the choice is kept in this browser only (`localStorage`). A browser that does not support it, or has blocked it, gets a plain explanation and the switch stays off.

### Desktop notifications (local)
`features/notifications/localNotifications.ts`: when a `MESSAGE_CREATED` event arrives, the pop-up is shown only if the message is from somebody else, is not a group "SYSTEM" note, the tab is **hidden**, the chat is **not muted**, the user switched desktop notifications on **and** the browser allowed them. The pop-up has the sender's name and the first 80 characters (or "Photo", "Voice message"...), one per chat (`tag`), and clicking it focuses the window and opens the chat without reloading (`utils/navigationBridge.ts`).

**What is NOT done:** these pop-ups are created by the open page itself. There is **no real push to a closed browser** (that needs FCM / Web Push and a service worker), and the web app does **not register a device token** with the server. The "Push notifications" switch only stores the preference for the future mobile app.

## Calls (Phase 7)
One-to-one voice and video calls (API_DOCUMENTATION.md 7.3, WEBSOCKET_DESIGN.md 9). The **sound and picture go straight from browser to browser** (WebRTC); the server only keeps the call's state and passes on the set-up messages. When a direct path is impossible the TURN server (Coturn) relays them; its address and a short-lived password come with the call (`iceServers`).

- **Starting**: the phone / camera buttons in a private chat header (not in groups) or the call-back buttons on `/calls`. The browser asks for the microphone (and camera) **first**; if that fails nothing is sent and the call screen says why ("Allow access to your camera and microphone ...", "No microphone found ...", "... is being used by another app"). Then `POST /api/calls`. A busy callee ends at once with "<name> is on another call"; `ALREADY_IN_CALL` / `CALL_NOT_ALLOWED` get their own sentence.
- **Ringing**: `CALL_INCOMING` on `/user/queue/calls` opens the call screen everywhere in the app, plays a soft ring tone (Web Audio, silent when the browser does not allow sound yet) and shows a desktop pop-up when the tab is hidden (the Phase 6 setting and permission). **Accept** asks for the microphone/camera first: if refused, the call keeps ringing and the screen says why. **Decline** closes the screen. If another device (or tab) of mine answers first, the call shows "Answered on another device".
- **Connecting** (`features/calls/callEngine.ts`): after `CALL_ACCEPTED` only the **caller's** tab creates the WebRTC offer; the callee answers the offer; both send their network addresses (ICE candidates) collected for 100 ms per request (`POST /api/calls/{id}/signal`). Candidates that arrive before the offer/answer are kept and added afterwards; signals that arrive before the accept answer are kept too. Events for calls this tab is not handling are ignored.
- **During the call**: the other person's video full size (or their picture in a voice call), my own video small in the corner (mirrored), a timer from `answeredAt`, **Mute**, **Turn camera off** (they switch the tracks off, no renegotiation) and **Hang up**. The app pings the server every 10 s. The connection state `failed`, or `disconnected` for more than 10 s, ends the call ("Connection lost"). The call screen is full screen on phones and a card (large for video) on bigger screens.
- **Ending**: every end stops the microphone and camera and closes the connection. The screen shows why for 3 s ("Call ended", "No answer", "Declined", "Call cancelled", "Missed call", "Connection lost") and closes itself (or with **Close**).
- **Page reload / close**: closing the page during a call ends it with `fetch(..., { keepalive: true })` (like the presence `/offline` call: `sendBeacon` cannot send the Authorization header). After a reload, and after every socket reconnect, the app asks `GET /api/calls/active`: the call this **tab** had before the reload (remembered in `sessionStorage`, key `chatapp.currentCallId`) is ended; an incoming call that is still ringing is shown; a call on another of my devices is left alone. Logout ends a running call before the tokens are cleared.
- **Missed calls**: the bell shows `MISSED_CALL` notifications ("Missed video call", phone icon); clicking one opens `/calls`. Settings > Notifications > "Missed calls" switches them off.
- **Debug switch: TURN only.** `localStorage.setItem('chatapp.iceTransportPolicy', 'relay')` makes this browser use **only** the TURN server (`iceTransportPolicy: 'relay'`). If a call still connects, Coturn and its credentials work. Remove the key to go back to normal. The end-to-end test uses it.

## Status / stories (Phase 8)
Contract: API_DOCUMENTATION.md 7.4. Page: `pages/status/StatusPage.tsx`, parts in `components/status/`, rules and state in `features/status/`.

- **Lists**: `GET /api/statuses/feed` and `/mine` through React Query, asked when the page opens and **every 30 s** while it is open (no live push). People with something unseen are under "Recent updates", the rest under "Viewed updates". The ring around a picture has one segment per status (green = not seen yet, grey = seen).
- **Posting text** (`TextStatusComposer`): up to 700 characters on one of the 8 fixed colours (a button cycles them). **Photo / video** (`MediaStatusComposer`): the file is checked with the same rules as chat attachments (`features/media/mediaRules.ts`), uploaded with a progress bar through `POST /api/media/upload`, then posted with the `mediaId` and an optional caption. Each post has a client-made `idempotencyKey`, so a repeated click or retry never creates two statuses.
- **Story viewer** (`StoryViewer`, state in `storyViewerState.ts`): one progress bar per status (5 s for text and photos, the video's own length for videos), the right / left half (or arrow keys) for next / previous, holding or Space pauses, Escape or X closes. It starts at the first status you have not seen and sends `POST /api/statuses/{id}/view` for each one shown (the ring turns grey at once). Pictures and videos come from 5-minute signed links (`useStatusMediaUrl`).
- **My own status**: the viewer has an eye button (viewer list, newest first; a note instead of the list when your read receipts are off) and a trash button with a confirmation.
- **Settings > Status privacy** (`StatusPrivacySection`): "My contacts" / "My contacts except..." / "Only share with..." with a contact picker, saved with `PUT /api/statuses/privacy`.

## Design system and layout (Phase 10: modern UI)
- **Tokens in one place:** `src/styles/index.css`. `brand-*` is the accent colour (violet-indigo), `gray-*` the cool blue-grey neutrals; change a value there and the whole app follows. Light and dark mode both use them (dark = `gray-950` page, `gray-900` panels). The first visit follows the system setting (a saved choice wins). Font: Inter (self-hosted through `@fontsource-variable/inter`, so the strict Content-Security-Policy still holds).
- **Fills the whole browser window.** `layouts/AppLayout.tsx` is `h-dvh w-full`: on a big screen a navigation column on the left that the user opens (icons + names) or closes (icons only, names as tooltips) with the toggle button at its top; the choice is remembered in `localStorage` (`chat-app-menu`), the first visit is open on windows wider than 1536 px and closed on narrower ones, and the width change is animated; the page area follows the width and the page uses ALL the remaining space; on a phone a slim top bar and a tab bar at the bottom. It is **one** navigation and one set of buttons that CSS moves (`display: contents`), so there are no duplicates (the browser tests rely on that). The chat page is list + chat with the chat taking the rest of the width; the other pages scroll inside the page area and use grids (contacts, calls, status feed as cards, settings in 2-3 columns, profile as two columns). `layouts/MainLayout.tsx` (login, register ...) is a split screen with a brand panel and the form.
- **Reusable pieces:** `components/common/` `PageHeader`, `Brand`, `Avatar` (a colour pair chosen from the name), `FormField`/`inputClass`, `SubmitButton` (`btn-brand`), dialogs; CSS classes `card`, `glass`, `chat-wallpaper`, `bg-aurora`, `skeleton`, `stagger`.
- **Animation** (all in `index.css`, no library): page fade-in on every section change, message bubbles pop in, dialogs and menus pop with a blurred backdrop, list items appear one after the other (`stagger`), badges bounce when a number changes, typing dots, a pulsing ring around the caller during ringing, online dot ping, skeleton loaders while the chat list loads, floating shapes on the login screen. `@media (prefers-reduced-motion: reduce)` switches them (nearly) off.
- **A gotcha worth knowing:** an animation that ends in a `transform` keeps that transform, and a `position: fixed` dialog inside such an element is positioned against it instead of the window (the sidebar was not dimmed). The animations therefore use `animation-fill-mode: backwards`, and the page wrapper animates only opacity.
- **Look at it without clicking:** `node e2e/tools/ui-screenshots.mjs <label> [desktop|laptop|tablet|phone]` (every screen, light and dark, into `ui-screenshots/<label>/`), `node e2e/tools/ui-interactions.mjs` (menus, dialogs, calls, stories) and `node e2e/tools/ui-menu-toggle.mjs` (opens and closes the menu, checks the widths and that the choice survives a reload) and `node e2e/tools/ui-fit-check.mjs`, which fails when any screen scrolls sideways or the frame is not exactly the window size (6 sizes from 320 px to 2560 px wide, all 8 screens). They log in with the accounts of `infra/DEMO_ACCOUNTS.md`.

## Emoji (Phase 13)
- **Picker:** `components/chat/EmojiButton.tsx` (the smiley next to the paperclip). Data: `features/emoji/emojiData.ts`, one line per emoji ("😀 grinning face happy": the text is the name AND what the search looks at); add a line to add an emoji. Groups, tabs, search (all typed words must match), and a "recently used" tab kept in `localStorage` (`chat-app-recent-emoji`, 24 emoji).
- **Insert at the cursor:** `Composer.tsx` `insertEmoji` uses the text box's selection (a selected piece is replaced) and puts the cursor behind the new emoji; it refuses to go over the 4000 characters.
- **Big emoji:** `bigEmojiCount` in `features/emoji/emojiText.ts` (graphemes via `Intl.Segmenter`) says when a message is only 1-3 emoji; `MessageBubble.tsx` then draws it big without a bubble.
- **Not in the picker on purpose:** country flags (Windows draws two letters instead).
- `node e2e/tools/ui-emoji.mjs` takes screenshots of the picker and of emoji messages.

## End-to-end tests (Playwright)
`e2e/chat.spec.ts` runs two real browser windows against the **running stack**: it registers two users through the API (reading the email code from MailHog), logs both in through the UI, starts a conversation from the contacts page, sends a message, and checks live delivery, the unread badge, the delivered and read ticks, a reply, the typing indicator (soft check) and that the history survives a reload.

`e2e/groups.spec.ts` registers three people, lets Alice create a group with Bob and Carol through the UI, and checks: Bob sees the group appear live with the "created group" note, Alice's message reaches Bob live with her name, Alice removes Carol (Bob sees the note), Carol keeps the earlier message but not the next one (checked through the API), and after Alice switches on "Only admins can send messages" Bob sees the banner instead of the composer. Shared helpers (register, read the email code, log in, add a contact) are in `e2e/helpers.ts`.

`e2e/media.spec.ts` (Phase 5): Alice sends a photo with a caption (Bob sees the picture load, opens the lightbox, closes it with Esc), a PDF (Bob sees the card and downloads it: the signed link answers 200 with `Content-Disposition: attachment`) and a voice note (recorded with Chrome's **fake microphone**, started by the flags in `playwright.config.ts`), then a program renamed `.png` (refused with a message, no bubble stays) and an 11 MB picture (refused before any upload). Small real files (a PNG and a PDF made in memory) come from `e2e/fixtures.ts`. It needs media-service and the object storage in the running stack.

`e2e/calls.spec.ts` (Phase 7) needs call-signaling-service, notification-service and Coturn in the running stack. Two browsers with Chrome's fake camera and microphone: (a) Alice video-calls Bob from the chat header, Bob accepts, both reach `data-connection-state="connected"` and the timer runs, Alice mutes, Bob hangs up, Alice's `/calls` shows the call with its duration; (b) the same with `chatapp.iceTransportPolicy = relay` in both browsers (only the TURN server may be used), proving Coturn works; (c) Bob declines and Alice sees "Declined"; (d) Alice cancels while it rings, Bob's screen says "Missed call", his bell gets "Missed video call" and his `/calls` shows it as missed. The call screen exposes `data-testid="call-overlay"` with `data-call-state` and `data-connection-state` for such tests.

`e2e/presence-notifications.spec.ts` (Phase 6) needs presence-service and notification-service in the running stack. Scenario 1: Bob is online for Alice, Bob logs out, Alice sees "last seen" within 25 s. Scenario 2: Bob is logged out while Alice sends two messages; when Bob logs in the bell shows 1, the list has one "2 new messages" entry titled with Alice, clicking it opens the chat and the bell goes back to 0. Scenario 3: Bob is online, Alice creates a group with him, his bell goes up live (no reload) and the panel says "<Alice> added you".

It drives the installed **Google Chrome** (`channel: 'chrome'`), so no browser download is needed.
```bash
docker compose -f ../infra/docker-compose.yml --env-file ../infra/.env up -d --build
npm run e2e                          # all specs
npx playwright test e2e/groups.spec.ts   # only the group flow
npx playwright test e2e/media.spec.ts    # only files and voice notes
npx playwright test e2e/presence-notifications.spec.ts   # presence and notifications
npx playwright test e2e/calls.spec.ts    # voice / video calls (direct and TURN only)
npx playwright test e2e/status.spec.ts   # status / stories (needs status-service)
```
Settings: `E2E_BASE_URL` (default `http://localhost:5280`, the Docker frontend; use `http://localhost:5210` together with `npm run dev`) and `E2E_MAILHOG_URL` (default `http://localhost:8025`). Failed runs keep screenshots and traces in `test-results/`.
