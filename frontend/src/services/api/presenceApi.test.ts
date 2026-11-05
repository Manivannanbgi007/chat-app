import { useAuthStore } from '../../store/authStore'
import { fakeTokens } from '../../test/renderWithProviders'
import { sendOffline } from './presenceApi'

// sendOffline uses fetch (not axios) so that the browser can finish it while the page is closing.

let fetchMock: ReturnType<typeof vi.fn>

beforeEach(() => {
  fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 200 }))
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.unstubAllGlobals()
  useAuthStore.getState().clearSession()
})

describe('sendOffline', () => {
  it('posts to /api/presence/offline with keepalive and the access token in the Authorization header', () => {
    useAuthStore.getState().setSession(fakeTokens({ accessToken: 'my-access-token' }), false)

    sendOffline()

    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, options] = fetchMock.mock.calls[0]
    expect(url).toBe('/api/presence/offline')
    expect(options.method).toBe('POST')
    expect(options.keepalive).toBe(true)
    expect(options.headers.Authorization).toBe('Bearer my-access-token')
    expect(options.headers['X-Correlation-Id']).toBeTruthy()
  })

  it('does nothing when nobody is logged in', () => {
    sendOffline()
    expect(fetchMock).not.toHaveBeenCalled()
  })

  it('never throws, even when the network call fails', async () => {
    useAuthStore.getState().setSession(fakeTokens(), false)
    fetchMock.mockRejectedValue(new Error('offline'))

    expect(() => sendOffline()).not.toThrow()
    await Promise.resolve()
  })

  it('never throws when fetch itself is missing or refuses', () => {
    useAuthStore.getState().setSession(fakeTokens(), false)
    fetchMock.mockImplementation(() => {
      throw new TypeError('keepalive not supported')
    })

    expect(() => sendOffline()).not.toThrow()
  })
})
