// k6 load test for chat-app. Run it with infra/load-test/run.sh (see README.md in this folder).
//
// Two kinds of virtual users (VUs):
//   chat   - one pair of people. B has a live WebSocket, A sends messages through the REST API; we measure how long a
//            message needs from "send" to "it arrived on B's socket" (the number that matters for a chat).
//   browse - people who only look around: profile, chat list, status feed, notifications, presence, heartbeat.
//
// PROFILE=baseline  1 pair + 1 browser for 30 s: the speed of an idle system (what "normal" feels like)
// PROFILE=load      ramps up to PAIRS pairs and BROWSERS browsers, holds, ramps down (default)
// PROFILE=stress    twice as many, to find where it starts to hurt
import http from 'k6/http'
import ws from 'k6/ws'
import { check, sleep } from 'k6'
import { Counter, Rate, Trend } from 'k6/metrics'
import { SharedArray } from 'k6/data'

const BASE = __ENV.BASE_URL || 'http://host.docker.internal:8200'
const WS_URL = BASE.replace(/^http/, 'ws') + '/ws'
const PROFILE = __ENV.PROFILE || 'load'
const PAIRS = parseInt(__ENV.PAIRS || '20')
const BROWSERS = parseInt(__ENV.BROWSERS || '20')
const SEND_MS = parseInt(__ENV.SEND_MS || '2000') // pause between two messages of one sender (smaller = more load)

const data = new SharedArray('pairs', () => JSON.parse(open('/data/users.json')).pairs)

const deliveryLatency = new Trend('chat_delivery_ms', true)       // send -> arrives on the receiver's WebSocket
const sendLatency = new Trend('chat_send_ms', true)               // time of the REST send call itself
const messagesSent = new Counter('chat_messages_sent')
const messagesReceived = new Counter('chat_messages_received')
const rateLimited = new Counter('rate_limited_429')
const wsConnected = new Rate('ws_connected')

const scale = PROFILE === 'stress' ? 2 : 1
const stages = (peak) => [
  { duration: '30s', target: Math.max(1, peak) },
  { duration: '90s', target: Math.max(1, peak) },
  { duration: '15s', target: 0 },
]

// Every virtual user needs its OWN pair (two users on the same pair would see each other's messages and spoil the
// numbers). k6 numbers the users of both scenarios together, so we need a pair for each of them.
export function setup() {
  const needed = (PROFILE === 'baseline' ? 2 : (Math.min(PAIRS, data.length) + BROWSERS) * scale)
  if (data.length < needed) {
    throw new Error(`Need at least ${needed} prepared pairs but data/users.json has ${data.length}. Run: python infra/load-test/prepare-users.py --pairs ${needed}`)
  }
}

export const options = {
  scenarios:
    PROFILE === 'baseline'
      ? {
          chat: { executor: 'constant-vus', vus: 1, duration: '30s', exec: 'chat' },
          browse: { executor: 'constant-vus', vus: 1, duration: '30s', exec: 'browse' },
        }
      : {
          chat: { executor: 'ramping-vus', startVUs: 0, stages: stages(Math.min(PAIRS, data.length) * scale), exec: 'chat', gracefulRampDown: '30s' },
          browse: { executor: 'ramping-vus', startVUs: 0, stages: stages(BROWSERS * scale), exec: 'browse', gracefulRampDown: '10s' },
        },
  thresholds: {
    http_req_failed: ['rate<0.01'],                                 // fewer than 1 % failed requests
    'http_req_duration{kind:send}': ['p(95)<500'],                  // sending: 95 % under 0.5 s
    'http_req_duration{kind:read}': ['p(95)<500'],
    chat_delivery_ms: ['p(95)<1000'],                               // arrival on the other phone: 95 % under 1 s
    ws_connected: ['rate>0.99'],
  },
}

function headers(token) {
  return { headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' } }
}

function uuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    return (c === 'x' ? r : (r & 0x3) | 0x8).toString(16)
  })
}

function count429(response) {
  if (response.status === 429) rateLimited.add(1)
}

// ---------------------------------------------------------------------------------------------------- chat
export function chat() {
  const pairIndex = (__VU - 1) % data.length
  const pair = data[pairIndex]
  const sendOptions = { ...headers(pair.a.token), tags: { kind: 'send' } }
  let opened = false

  const response = ws.connect(WS_URL, { headers: { 'Sec-WebSocket-Protocol': 'v12.stomp' } }, (socket) => {
    socket.on('open', () => {
      socket.send(`CONNECT\naccept-version:1.2\nheart-beat:0,0\nAuthorization:Bearer ${pair.b.token}\n\n\0`)
    })
    socket.on('message', (frame) => {
      if (frame.startsWith('CONNECTED')) {
        opened = true
        wsConnected.add(true)
        socket.send('SUBSCRIBE\nid:sub-0\ndestination:/user/queue/messages\n\n\0')
        // A sends one message every SEND_MS to 2 x SEND_MS milliseconds while the socket is open
        socket.setInterval(() => {
          const sentAt = Date.now()
          const res = http.post(
            `${BASE}/api/messages`,
            JSON.stringify({ conversationId: pair.conversationId, type: 'TEXT', content: `load ts=${sentAt} p=${pairIndex}`, idempotencyKey: uuid() }),
            sendOptions,
          )
          sendLatency.add(res.timings.duration)
          count429(res)
          if (check(res, { 'send is 200/201': (r) => r.status === 200 || r.status === 201 })) messagesSent.add(1)
        }, SEND_MS + Math.random() * SEND_MS)
      } else if (frame.startsWith('MESSAGE')) {
        // count only the messages of OUR pair (the same content marker as the sender above)
        const match = new RegExp('ts=(\\d+) p=' + pairIndex + '\\b').exec(frame)
        if (match) {
          deliveryLatency.add(Date.now() - parseInt(match[1]))
          messagesReceived.add(1)
        }
      } else if (frame.startsWith('ERROR')) {
        wsConnected.add(false)
        socket.close()
      }
    })
    socket.on('error', () => {
      if (!opened) wsConnected.add(false)
    })
    socket.setTimeout(() => socket.close(), 20000)   // one "session" of 20 s, then the VU starts a new one
  })
  check(response, { 'websocket upgraded (101)': (r) => r && r.status === 101 })
  if (!opened) wsConnected.add(false)

  // read the history like an app that opens the chat
  const history = http.get(`${BASE}/api/conversations/${pair.conversationId}/messages?limit=30`, { ...headers(pair.b.token), tags: { kind: 'read' } })
  count429(history)
  check(history, { 'history is 200': (r) => r.status === 200 })
  sleep(1)
}

// -------------------------------------------------------------------------------------------------- browse
export function browse() {
  const pair = data[(__VU - 1) % data.length]
  const me = pair.a
  const readOptions = { ...headers(me.token), tags: { kind: 'read' } }
  const requests = [
    ['GET', `${BASE}/api/users/me`],
    ['GET', `${BASE}/api/conversations`],
    ['GET', `${BASE}/api/statuses/feed`],
    ['GET', `${BASE}/api/notifications/unread-count`],
    ['GET', `${BASE}/api/presence/${pair.b.id}`],
    ['GET', `${BASE}/api/contacts`],
  ]
  for (const [method, url] of requests) {
    const res = http.request(method, url, null, readOptions)
    count429(res)
    check(res, { [`${url.split('/api/')[1].split('?')[0]} is 200`]: (r) => r.status === 200 })
    sleep(0.3 + Math.random() * 0.7)
  }
  const beat = http.post(`${BASE}/api/presence/heartbeat`, null, { ...headers(me.token), tags: { kind: 'read' } })
  count429(beat)
  sleep(2 + Math.random() * 3)
}
