// Manual check: do two message-service instances cooperate through Redis?
//
//   1. Start a SECOND message-service instance next to the one in Docker Compose (see TESTING_GUIDE.md), e.g. on port 8214.
//   2. node e2e/tools/multi-instance-check.mjs
//
// Bob's WebSocket connects to instance #2 (port 8214). Alice sends through the gateway, which goes to instance #1.
// If instance #2 still pushes the message to Bob, live delivery works across instances.
// Uses only Node 22+ (built-in fetch and WebSocket) and @stomp/stompjs from this project.

import { Client } from '@stomp/stompjs'

const GATEWAY = process.env.GATEWAY ?? 'http://localhost:8200'
const MAILHOG = process.env.MAILHOG ?? 'http://localhost:8025'
const SECOND_INSTANCE_WS = process.env.SECOND_INSTANCE_WS ?? 'ws://localhost:8214/ws'
const PASSWORD = 'Passw0rd123'

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))

async function api(method, path, body, token) {
  await sleep(550) // stay under the gateway's rate limit for /api/auth
  const response = await fetch(GATEWAY + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  })
  const json = await response.json().catch(() => null)
  return { status: response.status, data: json?.data }
}

async function readCode(email) {
  for (let i = 0; i < 30; i++) {
    const response = await fetch(`${MAILHOG}/api/v2/search?kind=to&query=${encodeURIComponent(email)}`)
    const { items } = await response.json()
    for (const item of items) {
      const match = /verification code is: (\d{6})/.exec(item.Content.Body)
      if (match) return match[1]
    }
    await sleep(500)
  }
  throw new Error('no verification mail for ' + email)
}

async function newUser(prefix) {
  const suffix = Math.random().toString(36).slice(2, 8)
  const user = { username: `mi_${prefix}_${suffix}`, email: `mi_${prefix}_${suffix}@example.com`, displayName: `${prefix} ${suffix}` }
  const registered = await api('POST', '/api/auth/register', { ...user, password: PASSWORD })
  if (registered.status !== 201) throw new Error('register failed ' + registered.status)
  const otp = await readCode(user.email)
  await api('POST', '/api/auth/verify-otp', { identifier: user.email, otp, purpose: 'EMAIL_VERIFY' })
  const login = await api('POST', '/api/auth/login', { identifier: user.email, password: PASSWORD })
  return { ...user, id: registered.data.userId, token: login.data.accessToken }
}

const alice = await newUser('alice')
const bob = await newUser('bob')

// Wait until bob's profile exists (created from a Kafka event), then alice opens a conversation
let conversation
for (let i = 0; i < 30 && !conversation; i++) {
  const created = await api('POST', '/api/conversations', { type: 'PRIVATE', userId: bob.id }, alice.token)
  if (created.status === 201 || created.status === 200) conversation = created.data
  else await sleep(500)
}
if (!conversation) throw new Error('could not create the conversation')

// Bob listens on instance #2
const received = []
const client = new Client({
  webSocketFactory: () => new WebSocket(SECOND_INSTANCE_WS),
  connectHeaders: { Authorization: `Bearer ${bob.token}` },
  reconnectDelay: 0,
})
const connected = new Promise((resolve, reject) => {
  client.onConnect = () => {
    client.subscribe('/user/queue/messages', (frame) => received.push(JSON.parse(frame.body)))
    resolve()
  }
  client.onStompError = (frame) => reject(new Error('STOMP error: ' + frame.headers.message))
  client.onWebSocketError = () => reject(new Error('cannot reach instance #2 at ' + SECOND_INSTANCE_WS))
})
client.activate()
await connected
await sleep(500)

// Alice sends through the gateway (instance #1)
const text = 'hello across instances ' + Date.now()
const sent = await api('POST', '/api/messages', {
  conversationId: conversation.id, type: 'TEXT', content: text, idempotencyKey: crypto.randomUUID(),
}, alice.token)
if (sent.status !== 201) throw new Error('send failed ' + sent.status)

const deadline = Date.now() + 8000
while (Date.now() < deadline && !received.some((event) => event.data?.content === text)) await sleep(100)
await client.deactivate()

if (received.some((event) => event.data?.content === text)) {
  console.log('PASS: bob (connected to instance #2) received the message sent through instance #1')
} else {
  console.error('FAIL: bob did not receive the message on instance #2')
  process.exit(1)
}
