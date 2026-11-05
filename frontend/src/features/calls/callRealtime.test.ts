import type { CallEngine } from './callEngine'
import { CALLS_DESTINATION, endCallBeforeLogout, startCalls, type CallConnection } from './callRealtime'

function fakeEngine() {
  return {
    handleEvent: vi.fn(),
    syncActiveCall: vi.fn(async () => undefined),
    onPageHide: vi.fn(),
    stop: vi.fn(),
  }
}

function fakeConnection() {
  const handlers = new Map<string, (event: unknown) => void>()
  let connected: () => void = () => undefined
  const connection: CallConnection = {
    subscribe: (destination, handler) => {
      handlers.set(destination, handler)
      return () => handlers.delete(destination)
    },
    onConnected: (listener) => {
      connected = listener
      return () => undefined
    },
  }
  return { connection, handlers, reconnect: () => connected() }
}

describe('startCalls', () => {
  it('listens on /user/queue/calls and passes the events to the engine', () => {
    const engine = fakeEngine()
    const { connection, handlers } = fakeConnection()

    startCalls(connection, engine as unknown as CallEngine)
    handlers.get(CALLS_DESTINATION)?.({ type: 'CALL_INCOMING', data: {} })

    expect(CALLS_DESTINATION).toBe('/user/queue/calls')
    expect(engine.handleEvent).toHaveBeenCalledWith({ type: 'CALL_INCOMING', data: {} })
  })

  it('asks "which call am I in?" at once and after every reconnect', () => {
    const engine = fakeEngine()
    const { connection, reconnect } = fakeConnection()

    startCalls(connection, engine as unknown as CallEngine)
    expect(engine.syncActiveCall).toHaveBeenCalledTimes(1)
    reconnect()
    expect(engine.syncActiveCall).toHaveBeenCalledTimes(2)
  })

  it('ends a call when the page closes, and stops everything on logout', () => {
    const engine = fakeEngine()
    const { connection, handlers } = fakeConnection()

    const stop = startCalls(connection, engine as unknown as CallEngine)
    window.dispatchEvent(new Event('pagehide'))
    expect(engine.onPageHide).toHaveBeenCalled()

    stop()
    expect(engine.stop).toHaveBeenCalled()
    expect(handlers.has(CALLS_DESTINATION)).toBe(false)
    window.dispatchEvent(new Event('pagehide'))
    expect(engine.onPageHide).toHaveBeenCalledTimes(1)
  })

  it('logout hangs up through the engine', () => {
    const engine = fakeEngine()
    endCallBeforeLogout(engine as unknown as CallEngine)
    expect(engine.stop).toHaveBeenCalled()
  })
})
