import { makeConversation, makeMessage, profile } from '../../test/chatFixtures'
import { arrangeChatList, formatDateTime, formatDayLabel, formatListTime, sortConversations } from './conversationList'

describe('sortConversations', () => {
  it('puts the conversation with the newest activity first', () => {
    const a = makeConversation({ id: 'a', createdAt: '2026-11-01T00:00:00.000000Z', otherUser: profile('x', 'A') })
    const b = makeConversation({ id: 'b', createdAt: '2026-11-01T00:00:00.000000Z', otherUser: profile('y', 'B') })
    const c = makeConversation({ id: 'c', createdAt: '2026-11-03T00:00:00.000000Z', otherUser: profile('z', 'C') })

    const sorted = sortConversations([a, b, c], {
      a: { unreadCount: 0, lastMessage: makeMessage({ createdAt: '2026-11-04T09:00:00.000000Z' }) },
      b: { unreadCount: 3, lastMessage: makeMessage({ createdAt: '2026-11-04T11:00:00.000000Z' }) },
    })

    // b (11:00) > a (09:00) > c (only created on 3 Nov, no messages)
    expect(sorted.map((x) => x.id)).toEqual(['b', 'a', 'c'])
  })

  it('falls back to lastMessageAt, then to createdAt, and does not change the input', () => {
    const withActivity = makeConversation({ id: 'p', lastMessageAt: '2026-11-04T08:00:00.000000Z' })
    const quiet = makeConversation({ id: 'q', createdAt: '2026-11-04T07:00:00.000000Z' })
    const input = [quiet, withActivity]

    expect(sortConversations(input, {}).map((x) => x.id)).toEqual(['p', 'q'])
    expect(input.map((x) => x.id)).toEqual(['q', 'p'])
  })
})

describe('arrangeChatList', () => {
  const prefs = (pinned: boolean, archived: boolean) => ({ mutedUntil: null, pinned, archived })

  it('puts pinned chats first and keeps archived chats apart, each part newest first', () => {
    const old = makeConversation({ id: 'old', createdAt: '2026-11-01T00:00:00.000000Z' })
    const recent = makeConversation({ id: 'recent', createdAt: '2026-11-04T00:00:00.000000Z' })
    const pinnedOld = makeConversation({ id: 'pinned-old', createdAt: '2026-10-01T00:00:00.000000Z', preferences: prefs(true, false) })
    const pinnedNew = makeConversation({ id: 'pinned-new', createdAt: '2026-10-02T00:00:00.000000Z', preferences: prefs(true, false) })
    const archived = makeConversation({ id: 'archived', createdAt: '2026-11-05T00:00:00.000000Z', preferences: prefs(false, true) })

    const { main, archived: folder } = arrangeChatList([old, recent, pinnedOld, pinnedNew, archived], {})

    expect(main.map((c) => c.id)).toEqual(['pinned-new', 'pinned-old', 'recent', 'old'])
    expect(folder.map((c) => c.id)).toEqual(['archived'])
  })
})

describe('time labels', () => {
  const now = new Date(2026, 10, 4, 15, 30)

  it('formatListTime: clock today, "Yesterday", else the date', () => {
    expect(formatListTime(new Date(2026, 10, 4, 9, 5).toISOString(), now)).toMatch(/9:05|09:05/)
    expect(formatListTime(new Date(2026, 10, 3, 23, 0).toISOString(), now)).toBe('Yesterday')
    expect(formatListTime(new Date(2026, 9, 20, 12, 0).toISOString(), now)).not.toBe('Yesterday')
  })

  it('formatDayLabel: Today, Yesterday, else a long date', () => {
    expect(formatDayLabel(new Date(2026, 10, 4, 1, 0).toISOString(), now)).toBe('Today')
    expect(formatDayLabel(new Date(2026, 10, 3, 1, 0).toISOString(), now)).toBe('Yesterday')
    expect(formatDayLabel(new Date(2026, 9, 1, 1, 0).toISOString(), now)).toContain('2026')
  })

  it('formatDateTime: only the clock today, day and clock otherwise', () => {
    expect(formatDateTime(new Date(2026, 10, 4, 9, 5).toISOString(), now)).toMatch(/^0?9:05/)
    expect(formatDateTime(new Date(2026, 10, 3, 9, 5).toISOString(), now)).toMatch(/^Yesterday, 0?9:05/)
  })
})
