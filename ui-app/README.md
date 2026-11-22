# Chat App UI (React + Vite + Tailwind, plain JavaScript)

The web frontend of the chat app, written in plain JavaScript (ES2023 + JSX). It is a one-to-one port of the
TypeScript app in `../frontend`: the same screens, the same API calls, the same behaviour, but without TypeScript.

## Run it

```bash
npm install
npm run dev        # http://localhost:5210  (needs the backend gateway on http://localhost:8200)
npm run build      # production build in dist/
npm run preview    # serve the production build
npm run lint       # oxlint: catches unused variables, broken hook rules, ...
```

The dev server forwards `/api` and `/ws` to the API gateway on port 8200 (see `vite.config.js`), so the browser only
ever talks to one origin. Copy `.env.example` to `.env` to point `VITE_API_BASE_URL` somewhere else.

## What is used

| Library                 | What for                                                                 |
| ----------------------- | ------------------------------------------------------------------------ |
| react, react-dom        | the UI                                                                   |
| react-router-dom        | pages and navigation (`src/routes/AppRoutes.jsx`)                        |
| tailwindcss (v4)        | styling; the colours, fonts and animations live in `src/styles/index.css` |
| @tanstack/react-query   | caching of server data (contacts, settings, signed file links, ...)      |
| zustand                 | small in-memory stores (login state, chats, notifications, theme)        |
| axios                   | HTTP calls, with the access-token / refresh logic in one place           |
| @stomp/stompjs          | the ONE WebSocket connection (live messages, receipts, calls, ...)       |
| lucide-react            | icons                                                                    |
| @fontsource-variable/inter | the font                                                              |

Compared with the TypeScript app, three libraries are gone: `react-hook-form`, `@hookform/resolvers` and `zod`.
Forms now use a small hook of our own, `src/hooks/useForm.js`, and plain validation functions in `src/validations/`.

## Where things are

```
src/
  main.jsx, App.jsx        start-up: React root, router, query client, "log me in again" on reload
  routes/                  the list of pages (AppRoutes) and the "logged-in only" guard (ProtectedRoute)
  layouts/                 MainLayout = login/register frame, AppLayout = the frame of every logged-in page
  pages/                   one folder per screen: auth, chat, status, calls, contacts, notifications, profile, settings
  components/              reusable pieces: common (buttons, dialogs, form field...), chat, calls, status, contacts, notifications
  features/                the logic behind the screens, by topic (messaging, calls, media, status, notifications, presence, emoji)
  services/api/            one file per backend service: every function returns the "data" part of the response
  services/websocket/      the WebSocket manager (reconnects, subscriptions)
  store/                   zustand stores: authStore, chatStore, notificationStore, themeStore
  hooks/                   small reusable hooks (useForm, useDebouncedValue, useCountdown)
  validations/             the form rules (return { fieldName: 'message' })
  utils/                   tiny helpers (storage, relative time, device name)
  types/                   NO code: JSDoc descriptions of the data the backend sends (look here for "what fields does X have?")
  styles/index.css         Tailwind + the design tokens (brand colours, animations, reusable classes like .card)
```

## How a form works (the only part that differs from the TypeScript app)

```jsx
const { register, registerCheckbox, handleSubmit, errors, isSubmitting } = useForm({
  initialValues: { identifier: '', password: '', rememberMe: false },
  validate: validateLogin, // returns { identifier: 'Enter your email...' } for the fields that are wrong
})

<form onSubmit={handleSubmit(onSubmit)} noValidate>
  <FormField label="Email" htmlFor="identifier" error={errors.identifier}>
    <input id="identifier" className={inputClass} {...register('identifier')} />
  </FormField>
  <input type="checkbox" {...registerCheckbox('rememberMe')} />
  <SubmitButton loading={isSubmitting}>Log in</SubmitButton>
</form>
```

`handleSubmit` runs `validate`, shows the messages, and only calls `onSubmit(values)` when everything is fine.
`setError(name, message)` shows a message that came back from the server (e.g. "email already used").
`reset(values)` fills the form once data has loaded (profile, privacy settings).

## Good to know

- Every failed API call becomes an `AppError` (`services/api/appError.js`) with a friendly `message`, a `kind`
  (`NETWORK`, `VALIDATION`, ...) and the backend `errorCode`. Screens only ever deal with that one error type.
- The access token lives in memory only; the refresh token is stored by `services/api/tokenStorage.js`.
  A 401 triggers ONE shared refresh and the failed request is retried once (`services/api/apiClient.js`).
- Live updates come through one STOMP WebSocket (`services/websocket/websocketManager.js`), wired to the stores by
  `features/messaging/realtime.js`, `features/notifications/notificationActions.js` and `features/calls/callRealtime.js`.
- Calls are WebRTC, browser to browser; `features/calls/callEngine.js` explains the whole flow at the top of the file.
- The app is responsive: a bottom tab bar on phones, a collapsible side menu on bigger screens (`layouts/AppLayout.jsx`).
