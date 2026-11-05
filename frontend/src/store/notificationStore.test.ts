import type { AppNotification, NotificationPage } from '../types/notification'
import { useNotificationStore } from './notificationStore'

const store = () => useNotificationStore.getState()

function make(id: string, overrides: Partial<AppNotification> = {}): AppNotification {
  return {
    id,
    type: 'NEW_MESSAGES',
    title: `Title ${id}`,
    body: '1 new message',
    conversationId: `c-${id}`,
    count: 1,
    read: false,
    createdAt: '2026-11-04T10:00:00Z',
    updatedAt: '2026-11-04T10:00:00Z',
    ...overrides,
  }
}

function page(items: AppNotification[], pageNumber = 0, hasMore = false): NotificationPage {
  return { items, page: pageNumber, size: 20, totalItems: items.length, hasMore }
}

beforeEach(() => store().reset())

describe('notificationStore: the list', () => {
  it('keeps the newest activity first', () => {
    store().setFirstPage(
      page([
        make('old', { updatedAt: '2026-11-04T08:00:00Z' }),
        make('new', { updatedAt: '2026-11-04T12:00:00Z' }),
        make('mid', { updatedAt: '2026-11-04T10:00:00Z' }),
      ]),
    )

    expect(store().items.map((item) => item.id)).toEqual(['new', 'mid', 'old'])
    expect(store().loaded).toBe(true)
  })

  it('appends the next page without duplicates and remembers the page number', () => {
    store().setFirstPage(page([make('a', { updatedAt: '2026-11-04T12:00:00Z' })], 0, true))
    store().appendPage(page([make('a', { updatedAt: '2026-11-04T12:00:00Z' }), make('b', { updatedAt: '2026-11-04T09:00:00Z' })], 1, false))

    expect(store().items.map((item) => item.id)).toEqual(['a', 'b'])
    expect(store().page).toBe(1)
    expect(store().hasMore).toBe(false)
  })

  it('a notification that arrived live while the first page loaded is kept', () => {
    store().applyLive(make('live', { updatedAt: '2026-11-04T13:00:00Z' }))
    store().setFirstPage(page([make('a', { updatedAt: '2026-11-04T09:00:00Z' })]))

    expect(store().items.map((item) => item.id)).toEqual(['live', 'a'])
  })
})

describe('notificationStore: live events and the badge', () => {
  it('a new unread notification adds one to the badge and goes to the top', () => {
    store().setUnreadCount(2)
    store().setFirstPage(page([make('a', { updatedAt: '2026-11-04T09:00:00Z' })]))

    store().applyLive(make('b', { updatedAt: '2026-11-04T11:00:00Z' }))

    expect(store().unreadCount).toBe(3)
    expect(store().items.map((item) => item.id)).toEqual(['b', 'a'])
  })

  it('an event for a notification we already have REPLACES it (the count grows in place, the badge stays)', () => {
    store().setUnreadCount(1)
    store().setFirstPage(page([make('a', { count: 1, body: '1 new message' })]))

    store().applyLive(make('a', { count: 3, body: '3 new messages', updatedAt: '2026-11-04T12:00:00Z' }))

    expect(store().items).toHaveLength(1)
    expect(store().items[0]).toMatchObject({ count: 3, body: '3 new messages' })
    expect(store().unreadCount).toBe(1)
  })

  it('a grown unread notification we have NOT loaded does not raise the badge (it was already counted)', () => {
    store().setUnreadCount(1)

    store().applyLive(make('a', { count: 4, body: '4 new messages' }))

    expect(store().unreadCount).toBe(1)
    expect(store().items).toHaveLength(1)
  })

  it('a notification that was read and is unread again counts again', () => {
    store().setFirstPage(page([make('a', { read: true })]))
    store().applyLive(make('a', { read: false, count: 1 }))

    expect(store().unreadCount).toBe(1)
  })

  it('an already read notification arriving live does not touch the badge', () => {
    store().applyLive(make('a', { read: true }))
    expect(store().unreadCount).toBe(0)
  })

  it('the badge never goes below zero', () => {
    store().setUnreadCount(-5)
    expect(store().unreadCount).toBe(0)
    store().markReadLocal('missing')
    expect(store().unreadCount).toBe(0)
  })
})

describe('notificationStore: reading', () => {
  it('marking one as read lowers the badge once, even when asked twice', () => {
    store().setUnreadCount(2)
    store().setFirstPage(page([make('a'), make('b')]))

    store().markReadLocal('a')
    store().markReadLocal('a')

    expect(store().unreadCount).toBe(1)
    expect(store().items.find((item) => item.id === 'a')?.read).toBe(true)
    expect(store().items.find((item) => item.id === 'b')?.read).toBe(false)
  })

  it('"mark all as read" clears everything', () => {
    store().setUnreadCount(2)
    store().setFirstPage(page([make('a'), make('b')]))

    store().markAllReadLocal()

    expect(store().unreadCount).toBe(0)
    expect(store().items.every((item) => item.read)).toBe(true)
  })

  it('reset forgets everything (logout)', () => {
    store().setUnreadCount(5)
    store().setFirstPage(page([make('a')]))

    store().reset()

    expect(store().unreadCount).toBe(0)
    expect(store().items).toEqual([])
    expect(store().loaded).toBe(false)
  })
})
