import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { getPresence } from '../../services/api/presenceApi'
import { webSocketManager } from '../../services/websocket/websocketManager'
import type { Presence } from '../../types/presence'
import { PRESENCE_REFRESH_MS, usePresence } from './usePresence'

vi.mock('../../services/api/presenceApi')
vi.mock('../../services/websocket/websocketManager', () => ({
  webSocketManager: { onConnected: vi.fn() },
}))
const getPresenceMock = vi.mocked(getPresence)
const onConnected = vi.mocked(webSocketManager.onConnected)

const online: Presence = { userId: 'u2', visible: true, online: true, lastSeenAt: null }

function setVisibility(state: 'visible' | 'hidden') {
  Object.defineProperty(document, 'visibilityState', { value: state, configurable: true })
}

function setup(userId: string | undefined) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  return renderHook(() => usePresence(userId), { wrapper })
}

beforeEach(() => {
  vi.resetAllMocks()
  getPresenceMock.mockResolvedValue(online)
  onConnected.mockReturnValue(() => undefined)
  setVisibility('visible')
})

afterEach(() => {
  vi.useRealTimers()
})

describe('usePresence', () => {
  it('asks once when the chat opens and returns the answer', async () => {
    const { result } = setup('u2')

    await waitFor(() => expect(result.current).toEqual(online))
    expect(getPresenceMock).toHaveBeenCalledWith('u2')
    expect(getPresenceMock).toHaveBeenCalledTimes(1)
  })

  it('asks nothing when there is no other user (a group)', () => {
    const { result } = setup(undefined)

    expect(result.current).toBeUndefined()
    expect(getPresenceMock).not.toHaveBeenCalled()
  })

  it('asks again every 10 seconds while the tab is visible and keeps the old answer meanwhile', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const { result } = setup('u2')
    await waitFor(() => expect(result.current).toEqual(online))

    getPresenceMock.mockResolvedValue({ ...online, online: false, lastSeenAt: '2026-11-04T10:32:00Z' })
    await vi.advanceTimersByTimeAsync(PRESENCE_REFRESH_MS + 100)

    expect(getPresenceMock).toHaveBeenCalledTimes(2)
    await waitFor(() => expect(result.current?.online).toBe(false))
    expect(PRESENCE_REFRESH_MS).toBe(10_000)
  })

  it('does not ask while the tab is hidden', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const { result } = setup('u2')
    await waitFor(() => expect(result.current).toEqual(online))

    setVisibility('hidden')
    await vi.advanceTimersByTimeAsync(PRESENCE_REFRESH_MS * 3)

    expect(getPresenceMock).toHaveBeenCalledTimes(1)
  })

  it('asks again after the WebSocket reconnects, and stops listening when the chat closes', async () => {
    const stopListening = vi.fn()
    onConnected.mockReturnValue(stopListening)
    const { result, unmount } = setup('u2')
    await waitFor(() => expect(result.current).toEqual(online))

    const reconnected = onConnected.mock.calls[0][0]
    reconnected()
    await waitFor(() => expect(getPresenceMock).toHaveBeenCalledTimes(2))

    unmount()
    expect(stopListening).toHaveBeenCalledTimes(1)
  })
})
