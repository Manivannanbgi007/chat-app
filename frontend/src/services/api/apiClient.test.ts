import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import { useAuthStore } from '../../store/authStore'
import { AppError } from './appError'
import { apiClient } from './apiClient'

// Replace the real HTTP call with a fake so we can look at the outgoing headers.
function fakeServer(status: number, body: unknown = {}) {
  let sent: InternalAxiosRequestConfig | undefined
  apiClient.defaults.adapter = async (config) => {
    sent = config
    const response = { data: body, status, statusText: '', headers: {}, config } as AxiosResponse
    if (status >= 400) {
      const { AxiosError } = await import('axios')
      throw new AxiosError('failed', 'ERR_BAD_REQUEST', config, undefined, response)
    }
    return response
  }
  return () => sent
}

describe('apiClient', () => {
  beforeEach(() => useAuthStore.setState({ accessToken: null }))

  it('adds a correlation id to every request', async () => {
    const getSent = fakeServer(200)
    await apiClient.get('/api/anything')
    expect(getSent()?.headers.get('X-Correlation-Id')).toMatch(/^[0-9a-f-]{36}$/)
  })

  it('sends no Authorization header when logged out', async () => {
    const getSent = fakeServer(200)
    await apiClient.get('/api/anything')
    expect(getSent()?.headers.get('Authorization')).toBeFalsy()
  })

  it('sends the Bearer token when logged in', async () => {
    useAuthStore.setState({ accessToken: 'abc123' })
    const getSent = fakeServer(200)
    await apiClient.get('/api/anything')
    expect(getSent()?.headers.get('Authorization')).toBe('Bearer abc123')
  })

  it('rejects failed calls with an AppError', async () => {
    fakeServer(403)
    await expect(apiClient.get('/api/secret')).rejects.toBeInstanceOf(AppError)
  })
})
