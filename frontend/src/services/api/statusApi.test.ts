import { AxiosError, type AxiosAdapter, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { useAuthStore } from '../../store/authStore'
import { fakeTokens } from '../../test/renderWithProviders'
import { makeStatus } from '../../test/statusFixtures'
import { AppError } from './appError'
import { apiClient } from './apiClient'
import {
  createStatus,
  deleteStatus,
  getMyStatuses,
  getStatusFeed,
  getStatusMediaUrl,
  getStatusPrivacy,
  getStatusViewers,
  markStatusViewed,
  updateStatusPrivacy,
} from './statusApi'

// The wrappers are thin; these tests pin the method, the path, the body / query and the unwrapping of "data".

const originalAdapter = apiClient.defaults.adapter
let requests: InternalAxiosRequestConfig[]
let answer: { status: number; data: unknown }

function respond(config: InternalAxiosRequestConfig): AxiosResponse {
  const response = { data: answer.data, status: answer.status, statusText: '', headers: {}, config } as AxiosResponse
  if (answer.status >= 400) throw new AxiosError('failed', 'ERR_BAD_REQUEST', config, undefined, response)
  return response
}

beforeEach(() => {
  requests = []
  answer = { status: 200, data: { success: true, message: 'ok', data: null, timestamp: '2026-11-04T10:00:00Z' } }
  apiClient.defaults.adapter = (async (config: InternalAxiosRequestConfig) => {
    requests.push(config)
    return respond(config)
  }) as AxiosAdapter
  useAuthStore.getState().setSession(fakeTokens(), false)
})

afterEach(() => {
  apiClient.defaults.adapter = originalAdapter
  useAuthStore.getState().clearSession()
})

function answerWith(data: unknown, status = 200) {
  answer = { status, data: { success: status < 400, message: 'x', data, timestamp: '2026-11-04T10:00:00Z' } }
}

describe('statusApi', () => {
  it('posts a text status with its idempotency key and returns the saved status', async () => {
    const saved = makeStatus()
    answerWith(saved, 201)

    const result = await createStatus({ type: 'TEXT', text: 'Hello', backgroundColor: '#1D4ED8', idempotencyKey: 'key-1' })

    expect(result).toEqual(saved)
    expect(requests[0].method).toBe('post')
    expect(requests[0].url).toBe('/api/statuses')
    expect(JSON.parse(requests[0].data)).toEqual({ type: 'TEXT', text: 'Hello', backgroundColor: '#1D4ED8', idempotencyKey: 'key-1' })
  })

  it('posts a photo status with the media id', async () => {
    answerWith(makeStatus({ type: 'IMAGE' }), 201)
    await createStatus({ type: 'IMAGE', mediaId: 'm-1', caption: 'Sea', idempotencyKey: 'key-2' })
    expect(JSON.parse(requests[0].data)).toEqual({ type: 'IMAGE', mediaId: 'm-1', caption: 'Sea', idempotencyKey: 'key-2' })
  })

  it('reads my statuses, the feed and the viewers', async () => {
    answerWith([])
    await getMyStatuses()
    await getStatusFeed()
    answerWith({ viewersHidden: false, viewCount: 0, viewers: [] })
    const viewers = await getStatusViewers('s-9')

    expect(requests.map((r) => `${r.method} ${r.url}`)).toEqual([
      'get /api/statuses/mine',
      'get /api/statuses/feed',
      'get /api/statuses/s-9/viewers',
    ])
    expect(viewers).toEqual({ viewersHidden: false, viewCount: 0, viewers: [] })
  })

  it('marks a status viewed and deletes one', async () => {
    await markStatusViewed('s-1')
    await deleteStatus('s-2')
    expect(requests.map((r) => `${r.method} ${r.url}`)).toEqual(['post /api/statuses/s-1/view', 'delete /api/statuses/s-2'])
  })

  it('asks for a signed media link with the variant', async () => {
    answerWith({ url: 'https://storage/x', expiresAt: '2026-11-04T10:05:00Z' })
    const link = await getStatusMediaUrl('s-3', 'thumbnail')
    expect(requests[0].url).toBe('/api/statuses/s-3/media-url')
    expect(requests[0].params).toEqual({ variant: 'thumbnail' })
    expect(link.url).toBe('https://storage/x')
  })

  it('reads and saves the status privacy', async () => {
    answerWith({ mode: 'CONTACTS', userIds: [] })
    expect(await getStatusPrivacy()).toEqual({ mode: 'CONTACTS', userIds: [] })
    answerWith({ mode: 'CONTACTS_EXCEPT', userIds: ['u-1'] })
    await updateStatusPrivacy({ mode: 'CONTACTS_EXCEPT', userIds: ['u-1'] })
    expect(requests[1].method).toBe('put')
    expect(requests[1].url).toBe('/api/statuses/privacy')
    expect(JSON.parse(requests[1].data)).toEqual({ mode: 'CONTACTS_EXCEPT', userIds: ['u-1'] })
  })

  it('turns an error answer into an AppError with the error code', async () => {
    answerWith({ errorCode: 'STATUS_LIMIT_REACHED' }, 409)
    const failure = await createStatus({ type: 'TEXT', text: 'x', backgroundColor: '#0F766E', idempotencyKey: 'k' }).catch((e) => e)
    expect(failure).toBeInstanceOf(AppError)
    expect(failure.errorCode).toBe('STATUS_LIMIT_REACHED')
  })
})
