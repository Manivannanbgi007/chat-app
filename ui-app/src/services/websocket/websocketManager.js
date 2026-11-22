import { Client } from '@stomp/stompjs'
import { refreshSession } from '../api/refreshSession'
import { useAuthStore } from '../../store/authStore'

// The ONE WebSocket connection of the app (STOMP over WebSocket, see WEBSOCKET_DESIGN.md).
//
// What this class takes care of:
// - exactly one connection, never two (start() twice does nothing)
// - the access token goes in the STOMP CONNECT header, never in the URL
// - reconnect after any failure with growing waits (1 s, 2 s, 4 s ... max 30 s) plus a random part
// - a fresh access token before every (re)connect (the server also closes sockets whose token expired)
// - subscriptions are kept in a registry by destination: no duplicates, and they come back after a reconnect
// - the connection state, so the UI can show "Reconnecting..."
//
// Connection states: 'offline' | 'connecting' | 'connected' | 'reconnecting'
//
// The manager gets everything it needs from the outside (`deps`), so the class itself never touches the
// network directly:
//   createConnection(url, token, { onConnect, onClose }) -> a stompjs Client (or something that looks like one)
//   getAccessToken() -> Promise<string>      buildUrl() -> string      random() -> number (0..1)

const MIN_DELAY_MS = 1000
const MAX_DELAY_MS = 30000

// How long to wait before reconnect attempt number `attempt` (0, 1, 2, ...):
// 1 s, 2 s, 4 s, 8 s ... up to 30 s. A random part (between half and the full wait) keeps thousands of
// clients from reconnecting at the same second after a server restart.
export function backoffDelay(attempt, random = Math.random) {
  const base = Math.min(MAX_DELAY_MS, MIN_DELAY_MS * 2 ** attempt)
  return Math.round(base / 2 + (random() * base) / 2)
}

export class WebSocketManager {
  constructor(deps) {
    this.deps = deps

    this.state = 'offline'
    this.stateListeners = new Set()
    this.connectedListeners = new Set()

    this.wanted = false // true between start() and stop()
    this.connection = null
    this.connecting = false // true while we wait for a token
    this.generation = 0 // lets us ignore callbacks of an old, replaced connection
    this.attempt = 0
    this.timer = null

    // destination -> everybody who wants its messages
    this.handlers = new Map()
    // destination -> the single STOMP subscription for it
    this.active = new Map()

    // Arrow functions, so they can be passed to useSyncExternalStore without losing `this`.
    this.getState = () => this.state
    this.onStateChange = (listener) => {
      this.stateListeners.add(listener)
      return () => this.stateListeners.delete(listener)
    }
  }

  // ---- state for the UI ----

  // Called after every successful (re)connect, once the subscriptions are in place.
  onConnected(listener) {
    this.connectedListeners.add(listener)
    return () => this.connectedListeners.delete(listener)
  }

  setState(state) {
    if (this.state === state) return
    this.state = state
    this.stateListeners.forEach((listener) => listener())
  }

  // ---- connect / disconnect ----

  // Opens the connection (and keeps it open, reconnecting when needed). Safe to call many times.
  start() {
    if (this.wanted) return
    this.wanted = true
    this.attempt = 0
    this.setState('connecting')
    this.connect()
  }

  // Closes the connection for good (logout).
  stop() {
    this.wanted = false
    this.generation++
    this.connecting = false
    this.attempt = 0
    this.clearTimer()
    this.dropConnection()
    this.setState('offline')
  }

  async connect() {
    if (this.connecting || this.connection) return // never two connections
    this.connecting = true
    const generation = ++this.generation

    let token
    try {
      token = await this.deps.getAccessToken()
    } catch {
      if (generation === this.generation) {
        this.connecting = false
        if (this.wanted) this.failAndRetry()
      }
      return
    }
    if (generation !== this.generation || !this.wanted) {
      return // stop() was called while we waited for the token
    }

    this.connecting = false
    this.connection = this.deps.createConnection(this.deps.buildUrl(), token, {
      onConnect: () => this.handleConnected(generation),
      onClose: () => this.handleClosed(generation),
    })
    this.connection.activate()
  }

  handleConnected(generation) {
    if (generation !== this.generation || !this.wanted) return
    this.attempt = 0
    this.setState('connected')
    this.active.clear() // a new connection has no subscriptions yet
    for (const destination of this.handlers.keys()) {
      this.subscribeOnConnection(destination)
    }
    this.connectedListeners.forEach((listener) => listener())
  }

  handleClosed(generation) {
    // The socket close and an ERROR frame can both arrive for the same failure: handle it once.
    if (generation !== this.generation || !this.wanted || !this.connection) return
    this.failAndRetry()
  }

  failAndRetry() {
    this.dropConnection()
    this.setState('reconnecting')
    if (this.timer) return
    this.timer = setTimeout(() => {
      this.timer = null
      this.connect()
    }, backoffDelay(this.attempt++, this.deps.random))
  }

  dropConnection() {
    const old = this.connection
    this.connection = null
    this.active.clear()
    if (old) {
      try {
        Promise.resolve(old.deactivate()).catch(() => undefined)
      } catch {
        // Already closed: nothing to do.
      }
    }
  }

  clearTimer() {
    if (this.timer) {
      clearTimeout(this.timer)
      this.timer = null
    }
  }

  // ---- subscriptions ----

  // Listen to a destination. Returns the function that stops listening.
  // Many listeners may share one destination: there is still only ONE STOMP subscription for it.
  subscribe(destination, handler) {
    let set = this.handlers.get(destination)
    if (!set) {
      set = new Set()
      this.handlers.set(destination, set)
    }
    set.add(handler)

    if (this.state === 'connected' && this.connection) {
      this.subscribeOnConnection(destination)
    }

    return () => {
      const current = this.handlers.get(destination)
      if (!current) return
      current.delete(handler)
      if (current.size === 0) {
        this.handlers.delete(destination)
        this.active.get(destination)?.unsubscribe()
        this.active.delete(destination)
      }
    }
  }

  subscribeOnConnection(destination) {
    if (!this.connection || this.active.has(destination)) return // no duplicate subscription
    const subscription = this.connection.subscribe(destination, (frame) => {
      let event
      try {
        event = JSON.parse(frame.body)
      } catch {
        return // not JSON: ignore
      }
      this.handlers.get(destination)?.forEach((handler) => handler(event))
    })
    this.active.set(destination, subscription)
  }

  // Sends a frame. Returns false when we are not connected (the caller decides what to do).
  publish(destination, body) {
    if (this.state !== 'connected' || !this.connection) return false
    this.connection.publish({ destination, body: JSON.stringify(body) })
    return true
  }
}

// ---- the real dependencies ----

// An access token that is valid for at least 30 more seconds. Refreshes (single-flight) when it is not.
export async function getFreshAccessToken() {
  const token = useAuthStore.getState().accessToken
  if (token && !expiresSoon(token)) return token
  return refreshSession()
}

// Reads the "exp" claim of a JWT (seconds since 1970). Unknown format -> treat as expiring.
function expiresSoon(token) {
  try {
    const payload = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/')))
    return typeof payload.exp !== 'number' || payload.exp * 1000 - Date.now() < 30_000
  } catch {
    return true
  }
}

function buildWebSocketUrl() {
  const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws'
  return `${scheme}://${window.location.host}/ws`
}

function createStompConnection(url, token, callbacks) {
  const client = new Client({
    brokerURL: url,
    connectHeaders: { Authorization: `Bearer ${token}` },
    heartbeatIncoming: 10000,
    heartbeatOutgoing: 10000,
    reconnectDelay: 0, // we reconnect ourselves (with a fresh token and backoff)
    onConnect: () => callbacks.onConnect(),
    onWebSocketClose: () => callbacks.onClose(),
    onWebSocketError: () => callbacks.onClose(),
    onStompError: () => callbacks.onClose(),
  })
  return client
}

export const webSocketManager = new WebSocketManager({
  createConnection: createStompConnection,
  getAccessToken: getFreshAccessToken,
  buildUrl: buildWebSocketUrl,
  random: Math.random,
})
