import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { fakeTokens } from '../../test/renderWithProviders'
import { useAuthStore } from '../../store/authStore'
import { AppError } from './appError'
import { apiClient } from './apiClient'
import { refreshClient } from './refreshSession'
import { getRefreshToken, saveRefreshToken } from './tokenStorage'

// Builds the response or the error that axios would produce for a status code.
function respond(config: InternalAxiosRequestConfig, status: number, data: unknown = {}) {
  const response = { data, status, statusText: '', headers: {}, config } as AxiosResponse
  if (status >= 400) {
    throw new AxiosError('failed', 'ERR_BAD_REQUEST', config, undefined, response)
  }
  return response
}

let refreshCalls: string[] // the refresh tokens sent to /api/auth/refresh

// Fake server: /api/data only accepts the access token "fresh-access".
function fakeBackend(refreshBehaviour: 'ok' | 'rejected' | 'network-down' = 'ok') {
  refreshCalls = []

  apiClient.defaults.adapter = async (config) => {
    if (config.url === '/api/auth/login') return respond(config, 401) // wrong password
    const ok = config.headers.get('Authorization') === 'Bearer fresh-access'
    return respond(config, ok ? 200 : 401, { success: true, data: 'secret' })
  }

  refreshClient.defaults.adapter = async (config) => {
    refreshCalls.push(JSON.parse(config.data).refreshToken)
    await new Promise((resolve) => setTimeout(resolve, 20)) // slow enough for calls to overlap
    if (refreshBehaviour === 'network-down') {
      throw new AxiosError('Network Error', 'ERR_NETWORK', config)
    }
    if (refreshBehaviour === 'rejected') return respond(config, 401)
    return respond(config, 200, {
      success: true,
      data: fakeTokens({ accessToken: 'fresh-access', refreshToken: 'refresh-2' }),
    })
  }
}

describe('apiClient: refreshing an expired access token', () => {
  beforeEach(() => {
    saveRefreshToken('refresh-1', false)
    useAuthStore.setState({ accessToken: 'expired-access', user: fakeTokens().user, restoring: false })
  })

  it('two parallel 401s cause only ONE refresh call, and both requests are retried', async () => {
    fakeBackend()

    const [first, second] = await Promise.all([apiClient.get('/api/data'), apiClient.get('/api/data')])

    expect(refreshCalls).toEqual(['refresh-1']) // never a second use of the same refresh token
    expect(first.data.data).toBe('secret')
    expect(second.data.data).toBe('secret')
  })

  it('stores the new tokens (the refresh token rotates)', async () => {
    fakeBackend()
    await apiClient.get('/api/data')

    expect(useAuthStore.getState().accessToken).toBe('fresh-access')
    expect(getRefreshToken()).toBe('refresh-2')
  })

  it('logs the user out when the refresh token is rejected', async () => {
    fakeBackend('rejected')

    await expect(apiClient.get('/api/data')).rejects.toBeInstanceOf(AppError)

    expect(useAuthStore.getState().accessToken).toBeNull()
    expect(getRefreshToken()).toBeNull()
  })

  it('does NOT log the user out when the server is just unreachable', async () => {
    fakeBackend('network-down')

    await expect(apiClient.get('/api/data')).rejects.toBeInstanceOf(AppError)

    expect(getRefreshToken()).toBe('refresh-1')
  })

  it('does not try to refresh when the login itself returns 401', async () => {
    fakeBackend()

    await expect(apiClient.post('/api/auth/login', {})).rejects.toMatchObject({ kind: 'UNAUTHORIZED' })

    expect(refreshCalls).toEqual([])
  })

  it('a later 401 starts a new refresh (the shared promise is released)', async () => {
    fakeBackend()
    await apiClient.get('/api/data')
    useAuthStore.setState({ accessToken: 'expired-again' })
    saveRefreshToken('refresh-2')

    await apiClient.get('/api/data')

    expect(refreshCalls).toEqual(['refresh-1', 'refresh-2'])
  })
})
