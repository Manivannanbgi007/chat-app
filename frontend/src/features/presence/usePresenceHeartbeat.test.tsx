import { act, renderHook } from '@testing-library/react'
import { sendHeartbeat, sendOffline } from '../../services/api/presenceApi'
import { useAuthStore } from '../../store/authStore'
import { fakeTokens } from '../../test/renderWithProviders'
import { HEARTBEAT_INTERVAL_MS, usePresenceHeartbeat } from './usePresenceHeartbeat'

vi.mock('../../services/api/presenceApi')
const heartbeat = vi.mocked(sendHeartbeat)
const offline = vi.mocked(sendOffline)

function setVisibility(state: 'visible' | 'hidden') {
  Object.defineProperty(document, 'visibilityState', { value: state, configurable: true })
}

function logIn() {
  useAuthStore.getState().setSession(fakeTokens(), false)
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.resetAllMocks()
  heartbeat.mockResolvedValue()
  setVisibility('visible')
  logIn()
})

afterEach(() => {
  vi.useRealTimers()
  useAuthStore.getState().clearSession()
})

describe('usePresenceHeartbeat', () => {
  it('sends a heartbeat right away and then every 20 seconds', async () => {
    renderHook(() => usePresenceHeartbeat())
    expect(heartbeat).toHaveBeenCalledTimes(1)

    await vi.advanceTimersByTimeAsync(HEARTBEAT_INTERVAL_MS - 1)
    expect(heartbeat).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(1)
    expect(heartbeat).toHaveBeenCalledTimes(2)
    await vi.advanceTimersByTimeAsync(HEARTBEAT_INTERVAL_MS)
    expect(heartbeat).toHaveBeenCalledTimes(3)
    expect(HEARTBEAT_INTERVAL_MS).toBe(20_000)
  })

  it('sends nothing while the tab is hidden, and one heartbeat as soon as it is visible again', async () => {
    renderHook(() => usePresenceHeartbeat())
    heartbeat.mockClear()

    setVisibility('hidden')
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.advanceTimersByTimeAsync(HEARTBEAT_INTERVAL_MS * 3)
    expect(heartbeat).not.toHaveBeenCalled()

    setVisibility('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    expect(heartbeat).toHaveBeenCalledTimes(1)
  })

  it('does not send when the app starts in a hidden tab', () => {
    setVisibility('hidden')
    renderHook(() => usePresenceHeartbeat())
    expect(heartbeat).not.toHaveBeenCalled()
  })

  it('ignores a failed heartbeat and simply tries again 20 seconds later', async () => {
    heartbeat.mockRejectedValueOnce(new Error('network down')).mockResolvedValue()
    renderHook(() => usePresenceHeartbeat())

    await vi.advanceTimersByTimeAsync(HEARTBEAT_INTERVAL_MS)

    expect(heartbeat).toHaveBeenCalledTimes(2)
  })

  it('marks the device offline when the page is closed', () => {
    renderHook(() => usePresenceHeartbeat())

    window.dispatchEvent(new Event('pagehide'))

    expect(offline).toHaveBeenCalledTimes(1)
  })

  it('stops completely when the page goes away (no timer, no listeners)', async () => {
    const { unmount } = renderHook(() => usePresenceHeartbeat())
    unmount()
    heartbeat.mockClear()

    await vi.advanceTimersByTimeAsync(HEARTBEAT_INTERVAL_MS * 2)
    document.dispatchEvent(new Event('visibilitychange'))
    window.dispatchEvent(new Event('pagehide'))

    expect(heartbeat).not.toHaveBeenCalled()
    expect(offline).not.toHaveBeenCalled()
  })

  it('never sends while logged out, and starts when the login happens', async () => {
    useAuthStore.getState().clearSession()
    renderHook(() => usePresenceHeartbeat())
    await vi.advanceTimersByTimeAsync(HEARTBEAT_INTERVAL_MS * 2)
    expect(heartbeat).not.toHaveBeenCalled()

    act(() => logIn())
    expect(heartbeat).toHaveBeenCalledTimes(1)
  })

  it('a refreshed access token does not restart the heartbeat', async () => {
    renderHook(() => usePresenceHeartbeat())
    expect(heartbeat).toHaveBeenCalledTimes(1)

    act(() => useAuthStore.getState().setAccessToken('another-access-token'))

    expect(heartbeat).toHaveBeenCalledTimes(1)
  })
})
