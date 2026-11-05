import { useAuthStore } from '../../store/authStore'
import { refreshSession } from '../api/refreshSession'
import {
  WebSocketManager,
  backoffDelay,
  getFreshAccessToken,
  type ConnectionCallbacks,
  type StompConnection,
} from './websocketManager'

vi.mock('../api/refreshSession')

// A fake STOMP connection that records what the manager does with it.
class FakeConnection implements StompConnection {
  activated = false
  deactivated = false
  subscribeCalls: string[] = []
  unsubscribed: string[] = []
  published: { destination: string; body: string }[] = []
  private callbacksByDestination = new Map<string, (frame: { body: string }) => void>()

  activate() {
    this.activated = true
  }
  deactivate() {
    this.deactivated = true
  }
  subscribe(destination: string, callback: (frame: { body: string }) => void) {
    this.subscribeCalls.push(destination)
    this.callbacksByDestination.set(destination, callback)
    return { unsubscribe: () => this.unsubscribed.push(destination) }
  }
  publish(parameters: { destination: string; body: string }) {
    this.published.push(parameters)
  }
  // Test helper: the server sends a frame.
  deliver(destination: string, body: unknown) {
    this.deliverRaw(destination, JSON.stringify(body))
  }
  deliverRaw(destination: string, body: string) {
    this.callbacksByDestination.get(destination)?.({ body })
  }
}

interface Created {
  url: string
  token: string
  callbacks: ConnectionCallbacks
  connection: FakeConnection
}

function setup(random = () => 1) {
  const created: Created[] = []
  const getAccessToken = vi.fn().mockResolvedValue('token-1')
  const manager = new WebSocketManager({
    createConnection: (url, token, callbacks) => {
      const connection = new FakeConnection()
      created.push({ url, token, callbacks, connection })
      return connection
    },
    getAccessToken,
    buildUrl: () => 'ws://app.test/ws',
    random,
  })
  return { manager, created, getAccessToken }
}

const flush = () => vi.advanceTimersByTimeAsync(0)

describe('WebSocketManager', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  it('opens exactly one connection even if start() is called many times', async () => {
    const { manager, created } = setup()

    manager.start()
    manager.start()
    manager.start()
    await flush()

    expect(created).toHaveLength(1)
    expect(created[0].connection.activated).toBe(true)
  })

  it('puts the token in the connect call and never in the URL', async () => {
    const { manager, created } = setup()

    manager.start()
    await flush()

    expect(created[0].token).toBe('token-1')
    expect(created[0].url).toBe('ws://app.test/ws')
    expect(created[0].url).not.toContain('token')
  })

  it('goes connecting -> connected and tells the listeners', async () => {
    const { manager, created } = setup()
    const states: string[] = []
    manager.onStateChange(() => states.push(manager.getState()))
    const onConnected = vi.fn()
    manager.onConnected(onConnected)

    manager.start()
    await flush()
    created[0].callbacks.onConnect()

    expect(states).toEqual(['connecting', 'connected'])
    expect(onConnected).toHaveBeenCalledTimes(1)
  })

  it('subscribes once per destination, even with several listeners and when asked before connecting', async () => {
    const { manager, created } = setup()
    const first = vi.fn()
    const second = vi.fn()
    manager.subscribe('/user/queue/messages', first) // before the connection exists
    manager.subscribe('/user/queue/messages', second)

    manager.start()
    await flush()
    created[0].callbacks.onConnect()
    manager.subscribe('/user/queue/messages', vi.fn()) // and again after connecting

    expect(created[0].connection.subscribeCalls).toEqual(['/user/queue/messages'])

    created[0].connection.deliver('/user/queue/messages', { type: 'X' })
    expect(first).toHaveBeenCalledWith({ type: 'X' })
    expect(second).toHaveBeenCalledWith({ type: 'X' })
  })

  it('only drops the STOMP subscription when the last listener leaves', async () => {
    const { manager, created } = setup()
    manager.start()
    await flush()
    created[0].callbacks.onConnect()
    const stopA = manager.subscribe('/topic/conversation/c1', vi.fn())
    const stopB = manager.subscribe('/topic/conversation/c1', vi.fn())

    stopA()
    expect(created[0].connection.unsubscribed).toEqual([])
    stopB()
    expect(created[0].connection.unsubscribed).toEqual(['/topic/conversation/c1'])
  })

  it('ignores frames that are not JSON', async () => {
    const { manager, created } = setup()
    const handler = vi.fn()
    manager.subscribe('/user/queue/messages', handler)
    manager.start()
    await flush()
    created[0].callbacks.onConnect()

    created[0].connection.deliverRaw('/user/queue/messages', 'not json')

    expect(handler).not.toHaveBeenCalled()
  })

  it('reconnects with a fresh token after the connection closes, and re-subscribes without duplicates', async () => {
    const { manager, created, getAccessToken } = setup()
    manager.subscribe('/user/queue/messages', vi.fn())
    manager.subscribe('/user/queue/receipts', vi.fn())
    manager.start()
    await flush()
    created[0].callbacks.onConnect()
    expect(getAccessToken).toHaveBeenCalledTimes(1)

    getAccessToken.mockResolvedValue('token-2') // the token was refreshed meanwhile
    created[0].callbacks.onClose()
    expect(manager.getState()).toBe('reconnecting')
    expect(created[0].connection.deactivated).toBe(true)

    await vi.advanceTimersByTimeAsync(1000) // first backoff step (random = 1 -> exactly 1 s)
    expect(created).toHaveLength(2)
    expect(created[1].token).toBe('token-2')
    expect(getAccessToken).toHaveBeenCalledTimes(2)

    created[1].callbacks.onConnect()
    expect(manager.getState()).toBe('connected')
    expect(created[1].connection.subscribeCalls).toEqual(['/user/queue/messages', '/user/queue/receipts'])
  })

  it('handles "ERROR frame" and "socket closed" of the same failure only once', async () => {
    const { manager, created } = setup()
    manager.start()
    await flush()
    created[0].callbacks.onConnect()

    created[0].callbacks.onClose() // STOMP ERROR frame
    created[0].callbacks.onClose() // then the socket close
    await vi.advanceTimersByTimeAsync(40_000)

    expect(created).toHaveLength(2) // one reconnect, not two
  })

  it('waits longer after every failed attempt', async () => {
    const { manager, created } = setup(() => 1)
    manager.start()
    await flush()
    created[0].callbacks.onClose() // fails before it ever connected

    await vi.advanceTimersByTimeAsync(999)
    expect(created).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(1) // 1 s
    expect(created).toHaveLength(2)

    created[1].callbacks.onClose()
    await vi.advanceTimersByTimeAsync(1999)
    expect(created).toHaveLength(2)
    await vi.advanceTimersByTimeAsync(1) // 2 s
    expect(created).toHaveLength(3)
  })

  it('starts counting from 1 s again after a successful connection', async () => {
    const { manager, created } = setup(() => 1)
    manager.start()
    await flush()
    created[0].callbacks.onClose()
    await vi.advanceTimersByTimeAsync(1000)
    created[1].callbacks.onClose()
    await vi.advanceTimersByTimeAsync(2000)
    created[2].callbacks.onConnect() // success resets the backoff

    created[2].callbacks.onClose()
    await vi.advanceTimersByTimeAsync(1000)

    expect(created).toHaveLength(4)
  })

  it('retries when the token cannot be fetched', async () => {
    const { manager, created, getAccessToken } = setup()
    getAccessToken.mockRejectedValueOnce(new Error('offline'))

    manager.start()
    await flush()
    expect(created).toHaveLength(0)
    expect(manager.getState()).toBe('reconnecting')

    await vi.advanceTimersByTimeAsync(1000)
    expect(created).toHaveLength(1)
  })

  it('stop() closes the connection, goes offline and cancels pending reconnects', async () => {
    const { manager, created } = setup()
    manager.start()
    await flush()
    created[0].callbacks.onConnect()
    created[0].callbacks.onClose() // a reconnect is scheduled

    manager.stop()
    await vi.advanceTimersByTimeAsync(60_000)

    expect(manager.getState()).toBe('offline')
    expect(created).toHaveLength(1)
  })

  it('stop() right after start() does not leave a connection behind', async () => {
    const { manager, created } = setup()

    manager.start()
    manager.stop() // e.g. React StrictMode mounting twice
    await flush()

    expect(created).toHaveLength(0)
    expect(manager.getState()).toBe('offline')
  })

  it('publish() works only while connected', async () => {
    const { manager, created } = setup()
    expect(manager.publish('/app/chat.typing', { typing: true })).toBe(false)

    manager.start()
    await flush()
    created[0].callbacks.onConnect()

    expect(manager.publish('/app/chat.typing', { typing: true })).toBe(true)
    expect(created[0].connection.published).toEqual([
      { destination: '/app/chat.typing', body: JSON.stringify({ typing: true }) },
    ])
  })
})

describe('backoffDelay', () => {
  it('grows 1 s, 2 s, 4 s ... and stops at 30 s (no jitter)', () => {
    const delays = [0, 1, 2, 3, 4, 5, 6, 10].map((attempt) => backoffDelay(attempt, () => 1))
    expect(delays).toEqual([1000, 2000, 4000, 8000, 16000, 30000, 30000, 30000])
  })

  it('jitter keeps the delay between half and the full wait', () => {
    for (const attempt of [0, 1, 2, 5, 9]) {
      const full = Math.min(30000, 1000 * 2 ** attempt)
      expect(backoffDelay(attempt, () => 0)).toBe(full / 2)
      expect(backoffDelay(attempt, () => 1)).toBe(full)
      const middle = backoffDelay(attempt)
      expect(middle).toBeGreaterThanOrEqual(full / 2)
      expect(middle).toBeLessThanOrEqual(full)
    }
  })
})

describe('getFreshAccessToken', () => {
  function jwtExpiringIn(seconds: number): string {
    const payload = btoa(JSON.stringify({ exp: Math.floor(Date.now() / 1000) + seconds }))
    return `header.${payload}.signature`
  }

  beforeEach(() => {
    vi.mocked(refreshSession).mockReset()
    useAuthStore.setState({ accessToken: null })
  })

  it('returns the current token without refreshing when it is still valid', async () => {
    const token = jwtExpiringIn(600)
    useAuthStore.setState({ accessToken: token })

    await expect(getFreshAccessToken()).resolves.toBe(token)
    expect(refreshSession).not.toHaveBeenCalled()
  })

  it('refreshes when the token is about to expire', async () => {
    useAuthStore.setState({ accessToken: jwtExpiringIn(10) })
    vi.mocked(refreshSession).mockResolvedValue('new-token')

    await expect(getFreshAccessToken()).resolves.toBe('new-token')
  })

  it('refreshes when there is no token or it cannot be read', async () => {
    vi.mocked(refreshSession).mockResolvedValue('new-token')

    await expect(getFreshAccessToken()).resolves.toBe('new-token')
    useAuthStore.setState({ accessToken: 'not-a-jwt' })
    await expect(getFreshAccessToken()).resolves.toBe('new-token')
    expect(refreshSession).toHaveBeenCalledTimes(2)
  })
})
