import { AxiosError, type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { AppError } from '../../services/api/appError'
import { makeGroup, makeStatus } from '../../test/statusFixtures'
import {
  describeStatusError,
  firstUnviewedIndex,
  formatStatusTime,
  markViewedInFeed,
  nextColor,
  splitFeed,
  STATUS_COLORS,
  statusKindOf,
} from './statusRules'

describe('nextColor', () => {
  it('walks through the 8 colours and wraps around', () => {
    expect(STATUS_COLORS).toHaveLength(8)
    expect(nextColor(STATUS_COLORS[0])).toBe(STATUS_COLORS[1])
    expect(nextColor(STATUS_COLORS[7])).toBe(STATUS_COLORS[0])
    expect(nextColor('#123456')).toBe(STATUS_COLORS[0]) // unknown -> the first
  })
})

describe('formatStatusTime', () => {
  const now = new Date(2026, 10, 4, 15, 30, 0)
  const clock = (date: Date) => date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })

  it('says "Just now" for the first minute and counts minutes for the first hour', () => {
    expect(formatStatusTime(new Date(2026, 10, 4, 15, 29, 30).toISOString(), now)).toBe('Just now')
    expect(formatStatusTime(new Date(2026, 10, 4, 15, 29, 0).toISOString(), now)).toBe('1 minute ago')
    expect(formatStatusTime(new Date(2026, 10, 4, 15, 18, 0).toISOString(), now)).toBe('12 minutes ago')
  })

  it('uses "Today" and "Yesterday" with the time after an hour', () => {
    const today = new Date(2026, 10, 4, 10, 42, 0)
    const yesterday = new Date(2026, 10, 3, 18, 5, 0)
    expect(formatStatusTime(today.toISOString(), now)).toBe(`Today ${clock(today)}`)
    expect(formatStatusTime(yesterday.toISOString(), now)).toBe(`Yesterday ${clock(yesterday)}`)
  })

  it('treats a time slightly in the future (clock differences) as just now, and bad input as empty', () => {
    expect(formatStatusTime(new Date(2026, 10, 4, 15, 31, 0).toISOString(), now)).toBe('Just now')
    expect(formatStatusTime('not a date', now)).toBe('')
  })
})

describe('splitFeed', () => {
  it('puts people with something unseen in "recent" and the rest in "viewed", newest first', () => {
    const old = makeGroup('u-a', 'Asha', [makeStatus({ createdAt: '2026-11-04T08:00:00Z', viewedByMe: false })])
    const fresh = makeGroup('u-b', 'Ravi', [
      makeStatus({ createdAt: '2026-11-04T09:00:00Z', viewedByMe: true }),
      makeStatus({ createdAt: '2026-11-04T11:00:00Z', viewedByMe: false }),
    ])
    const seen = makeGroup('u-c', 'Meena', [makeStatus({ createdAt: '2026-11-04T10:00:00Z', viewedByMe: true })])

    const { recent, viewed } = splitFeed([old, seen, fresh])

    expect(recent.map((g) => g.owner.displayName)).toEqual(['Ravi', 'Asha'])
    expect(viewed.map((g) => g.owner.displayName)).toEqual(['Meena'])
  })

  it('decides from the statuses themselves, not from a stale allViewed flag, and skips empty groups', () => {
    const group = { ...makeGroup('u-a', 'Asha', [makeStatus({ viewedByMe: true })]), allViewed: false }
    const empty = { ...makeGroup('u-b', 'Ravi', []), statuses: [] }
    expect(splitFeed([group, empty])).toEqual({ recent: [], viewed: [group] })
  })
})

describe('markViewedInFeed', () => {
  it('marks one status seen and recomputes allViewed', () => {
    const first = makeStatus()
    const second = makeStatus()
    const groups = [makeGroup('u-a', 'Asha', [first, second])]

    const once = markViewedInFeed(groups, first.id)
    expect(once[0].statuses.map((s) => s.viewedByMe)).toEqual([true, false])
    expect(once[0].allViewed).toBe(false)

    const twice = markViewedInFeed(once, second.id)
    expect(twice[0].allViewed).toBe(true)
  })

  it('returns the same array when nothing changes (no needless re-render)', () => {
    const groups = [makeGroup('u-a', 'Asha', [makeStatus({ viewedByMe: true })])]
    expect(markViewedInFeed(groups, groups[0].statuses[0].id)).toBe(groups)
    expect(markViewedInFeed(groups, 'unknown')).toBe(groups)
  })
})

describe('firstUnviewedIndex', () => {
  it('starts at the first unseen status, or at the first one when everything was seen', () => {
    expect(firstUnviewedIndex([makeStatus({ viewedByMe: true }), makeStatus(), makeStatus()])).toBe(1)
    expect(firstUnviewedIndex([makeStatus({ viewedByMe: true }), makeStatus({ viewedByMe: true })])).toBe(0)
  })
})

describe('statusKindOf', () => {
  it('knows photos and videos by type or by extension', () => {
    expect(statusKindOf({ type: 'image/png', name: 'a.png' })).toBe('IMAGE')
    expect(statusKindOf({ type: 'video/mp4', name: 'a.mp4' })).toBe('VIDEO')
    expect(statusKindOf({ type: '', name: 'clip.MOV' })).toBe('VIDEO')
    expect(statusKindOf({ type: '', name: 'photo.jpeg' })).toBe('IMAGE')
    expect(statusKindOf({ type: 'application/pdf', name: 'a.pdf' })).toBeNull()
  })
})

describe('describeStatusError', () => {
  function serverError(status: number, data: unknown): AxiosError {
    const config = { headers: {} } as InternalAxiosRequestConfig
    const response = { data: { success: false, message: 'Server says no', data }, status, statusText: '', headers: {}, config } as AxiosResponse
    return new AxiosError('failed', 'ERR_BAD_REQUEST', config, undefined, response)
  }

  it('explains the status error codes', () => {
    expect(describeStatusError(serverError(409, { errorCode: 'STATUS_LIMIT_REACHED' }))).toMatch(/30 active status updates/)
    expect(describeStatusError(serverError(404, { errorCode: 'ATTACHMENT_NOT_FOUND' }))).toMatch(/no longer available/)
    expect(describeStatusError(serverError(400, { errorCode: 'ATTACHMENT_TYPE_MISMATCH' }))).toMatch(/does not match/)
    expect(describeStatusError(serverError(409, { errorCode: 'ATTACHMENT_ALREADY_USED' }))).toMatch(/already used/)
  })

  it('uses the upload texts for refused files, and plain words for network and server problems', () => {
    expect(describeStatusError(serverError(415, { errorCode: 'UNSUPPORTED_TYPE' }))).toBe('This type of file is not allowed.')
    expect(describeStatusError(new AppError('NETWORK', 'offline'))).toMatch(/No connection/)
    expect(describeStatusError(serverError(503, { errorCode: 'DEPENDENCY_UNAVAILABLE' }))).toMatch(/not available right now/)
    expect(describeStatusError(serverError(400, { text: 'must not be blank' }))).toBe('Server says no')
  })
})
