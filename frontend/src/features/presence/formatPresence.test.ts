import type { Presence } from '../../types/presence'
import { formatLastSeen, presenceLine } from './formatPresence'

// All times are built in LOCAL time (like the function), so the tests pass in any time zone.
const NOW = new Date(2026, 10, 4, 15, 30, 0) // 4 Nov 2026, 15:30 local

function local(year: number, monthIndex: number, day: number, hour: number, minute: number): string {
  return new Date(year, monthIndex, day, hour, minute).toISOString()
}

describe('formatLastSeen', () => {
  it('today: "last seen today at HH:mm" (24 hours, with a leading zero)', () => {
    expect(formatLastSeen(local(2026, 10, 4, 10, 32), NOW)).toBe('last seen today at 10:32')
    expect(formatLastSeen(local(2026, 10, 4, 9, 5), NOW)).toBe('last seen today at 09:05')
    expect(formatLastSeen(local(2026, 10, 4, 0, 0), NOW)).toBe('last seen today at 00:00')
  })

  it('yesterday: "last seen yesterday at HH:mm", also across midnight', () => {
    expect(formatLastSeen(local(2026, 10, 3, 21, 5), NOW)).toBe('last seen yesterday at 21:05')
    // 23:59 yesterday seen from 00:10 today is still "yesterday"
    expect(formatLastSeen(local(2026, 10, 3, 23, 59), new Date(2026, 10, 4, 0, 10))).toBe('last seen yesterday at 23:59')
  })

  it('older: the date, without the year in the current year', () => {
    expect(formatLastSeen(local(2026, 10, 2, 18, 20), NOW)).toBe('last seen 02 Nov at 18:20')
    expect(formatLastSeen(local(2026, 0, 9, 7, 45), NOW)).toBe('last seen 09 Jan at 07:45')
  })

  it('older and in another year: the year is shown', () => {
    expect(formatLastSeen(local(2025, 10, 3, 18, 20), NOW)).toBe('last seen 03 Nov 2025 at 18:20')
  })

  it('a time in the future (the two clocks differ) is shown as now, never as the future', () => {
    expect(formatLastSeen(local(2026, 10, 4, 15, 34), NOW)).toBe('last seen today at 15:30')
    // even a day ahead
    expect(formatLastSeen(local(2026, 10, 5, 3, 0), NOW)).toBe('last seen today at 15:30')
  })

  it('a time that is not a date gives nothing', () => {
    expect(formatLastSeen('not a date', NOW)).toBeNull()
  })
})

describe('presenceLine', () => {
  const base: Presence = { userId: 'u', visible: true, online: false, lastSeenAt: local(2026, 10, 4, 10, 32) }

  it('online', () => {
    expect(presenceLine({ ...base, online: true }, NOW)).toEqual({ kind: 'online' })
  })

  it('offline with a last seen time', () => {
    expect(presenceLine(base, NOW)).toEqual({ kind: 'lastSeen', text: 'last seen today at 10:32' })
  })

  it('nothing when the privacy settings hide it', () => {
    expect(presenceLine({ userId: 'u', visible: false, online: null, lastSeenAt: null }, NOW)).toEqual({ kind: 'none' })
  })

  it('nothing when the person was never seen', () => {
    expect(presenceLine({ ...base, lastSeenAt: null }, NOW)).toEqual({ kind: 'none' })
  })

  it('nothing while the first answer is still loading', () => {
    expect(presenceLine(undefined, NOW)).toEqual({ kind: 'none' })
  })
})
