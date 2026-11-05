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

export type ConnectionState = 'offline' | 'connecting' | 'connected' | 'reconnecting'

export type MessageHandler = (event: unknown) => void

// The small part of the stompjs Client that we use. Tests replace it with a fake.
export interface StompConnection {
  activate(): void
  deactivate(): Promise<void> | void
  subscribe(destination: string, callback: (frame: { body: string }) => void): { unsubscribe(): void }
  publish(parameters: { destination: string; body: string }): void
}

export interface ConnectionCallbacks {
  onConnect: () => void
  // The socket closed or the server sent an ERROR frame: either way we have to reconnect.
  onClose: () => void
}

export interface ManagerDeps {
  createConnection(url: string, token: string, callbacks: ConnectionCallbacks): StompConnection
  getAccessToken(): Promise<string>
  buildUrl(): string
  random(): number
}

const MIN_DELAY_MS = 1000
const MAX_DELAY_MS = 30000

// How long to wait before reconnect attempt number `attempt` (0, 1, 2, ...):
// 1 s, 2 s, 4 s, 8 s ... up to 30 s. A random part (between half and the full wait) keeps thousands of
// clients from reconnecting at the same second after a server restart.
export function backoffDelay(attempt: number, random: () => number = Math.random): number {
  const base = Math.min(MAX_DELAY_MS, MIN_DELAY_MS * 2 ** attempt)
  return Math.round(base / 2 + (random() * base) / 2)
}

export class WebSocketManager {
  private state: ConnectionState = 'offline'
  private stateListeners = new Set<() => void>()
  private connectedListeners = new Set<() => void>()

  private wanted = false // true between start() and stop()
  private connection: StompConnection | null = null
  private connecting = false // true while we wait for a token
  private generation = 0 // lets us ignore callbacks of an old, replaced connection
  private attempt = 0
  private timer: ReturnType<typeof setTimeout> | null = null

  // destination -> everybody who wants its messages
  private handlers = new Map<string, Set<MessageHandler>>()
  // destination -> the single STOMP subscription for it
  private active = new Map<string, { unsubscribe(): void }>()

  private deps: ManagerDeps

  constructor(deps: ManagerDeps) {
    this.deps = deps
  }

  // ---- state for the UI ----

  getState = (): ConnectionState => this.state

  onStateChange = (listener: () => void): (() => void) => {
    this.stateListeners.add(listener)
    return () => this.stateListeners.delete(listener)
  }

  // Called after every successful (re)connect, once the subscriptions are in place.
  onConnected(listener: () => void): () => void {
    this.connectedListeners.add(listener)
    return () => this.connectedListeners.delete(listener)
  }

  private setState(state: ConnectionState) {
    if (this.state === state) return
    this.state = state
    this.stateListeners.forEach((listener) => listener())
  }

  // ---- connect / disconnect ----

  // Opens the connection (and keeps it open, reconnecting when needed). Safe to call many times.
  start(): void {
    if (this.wanted) return
    this.wanted = true
    this.attempt = 0
    this.setState('connecting')
    void this.connect()
  }

  // Closes the connection for good (logout).
  stop(): void {
    this.wanted = false
    this.generation++
    this.connecting = false
    this.attempt = 0
    this.clearTimer()
    this.dropConnection()
    this.setState('offline')
  }

  private async connect(): Promise<void> {
    if (this.connecting || this.connection) return // never two connections
    this.connecting = true
    const generation = ++this.generation

    let token: string
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

  private handleConnected(generation: number) {
    if (generation !== this.generation || !this.wanted) return
    this.attempt = 0
    this.setState('connected')
    this.active.clear() // a new connection has no subscriptions yet
    for (const destination of this.handlers.keys()) {
      this.subscribeOnConnection(destination)
    }
    this.connectedListeners.forEach((listener) => listener())
  }

  private handleClosed(generation: number) {
    // The socket close and an ERROR frame can both arrive for the same failure: handle it once.
    if (generation !== this.generation || !this.wanted || !this.connection) return
    this.failAndRetry()
  }

  private failAndRetry() {
    this.dropConnection()
    this.setState('reconnecting')
    if (this.timer) return
    this.timer = setTimeout(() => {
      this.timer = null
      void this.connect()
    }, backoffDelay(this.attempt++, this.deps.random))
  }

  private dropConnection() {
    const old = this.connection
    this.connection = null
    this.active.clear()
    if (old) {
      try {
        void Promise.resolve(old.deactivate()).catch(() => undefined)
      } catch {
        // Already closed: nothing to do.
      }
    }
  }

  private clearTimer() {
    if (this.timer) {
      clearTimeout(this.timer)
      this.timer = null
    }
  }

  // ---- subscriptions ----

  // Listen to a destination. Returns the function that stops listening.
  // Many listeners may share one destination: there is still only ONE STOMP subscription for it.
  subscribe(destination: string, handler: MessageHandler): () => void {
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

  private subscribeOnConnection(destination: string) {
    if (!this.connection || this.active.has(destination)) return // no duplicate subscription
    const subscription = this.connection.subscribe(destination, (frame) => {
      let event: unknown
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
  publish(destination: string, body: unknown): boolean {
    if (this.state !== 'connected' || !this.connection) return false
    this.connection.publish({ destination, body: JSON.stringify(body) })
    return true
  }
}

// ---- the real dependencies ----

// An access token that is valid for at least 30 more seconds. Refreshes (single-flight) when it is not.
export async function getFreshAccessToken(): Promise<string> {
  const token = useAuthStore.getState().accessToken
  if (token && !expiresSoon(token)) return token
  return refreshSession()
}

// Reads the "exp" claim of a JWT (seconds since 1970). Unknown format -> treat as expiring.
function expiresSoon(token: string): boolean {
  try {
    const payload = JSON.parse(atob(token.split('.')[1].replace(/-/g, '+').replace(/_/g, '/'))) as { exp?: number }
    return typeof payload.exp !== 'number' || payload.exp * 1000 - Date.now() < 30_000
  } catch {
    return true
  }
}

function buildWebSocketUrl(): string {
  const scheme = window.location.protocol === 'https:' ? 'wss' : 'ws'
  return `${scheme}://${window.location.host}/ws`
}

function createStompConnection(url: string, token: string, callbacks: ConnectionCallbacks): StompConnection {
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
