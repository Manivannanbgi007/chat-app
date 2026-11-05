import { relativeTime } from './relativeTime'

const NOW = new Date(2026, 10, 4, 15, 30, 0) // 4 Nov 2026, 15:30 local

function ago(seconds: number): string {
  return new Date(NOW.getTime() - seconds * 1000).toISOString()
}

describe('relativeTime', () => {
  it('under a minute (and small clock differences): just now', () => {
    expect(relativeTime(ago(5), NOW)).toBe('just now')
    expect(relativeTime(ago(-20), NOW)).toBe('just now') // a few seconds "in the future"
  })

  it('minutes and hours', () => {
    expect(relativeTime(ago(5 * 60), NOW)).toBe('5 min ago')
    expect(relativeTime(ago(3 * 3600 + 20), NOW)).toBe('3 h ago')
  })

  it('yesterday, then the date', () => {
    expect(relativeTime(new Date(2026, 10, 3, 9, 0).toISOString(), NOW)).toBe('yesterday')
    expect(relativeTime(new Date(2026, 10, 2, 9, 0).toISOString(), NOW)).toBe('02 Nov')
  })

  it('an invalid time gives an empty text', () => {
    expect(relativeTime('nonsense', NOW)).toBe('')
  })
})
