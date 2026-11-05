import * as notificationApi from '../../services/api/notificationApi'
import { useNotificationStore } from '../../store/notificationStore'
import type { AppNotification, NotificationPage } from '../../types/notification'
import {
  handleNotificationEvent,
  loadFirstNotifications,
  loadMoreNotifications,
  markAllNotificationsRead,
  openNotification,
  refreshUnreadCount,
  refreshUnreadCountAfterOpeningChat,
  startNotifications,
  type NotificationConnection,
} from './notificationActions'

vi.mock('../../services/api/notificationApi')
const api = vi.mocked(notificationApi)
const store = () => useNotificationStore.getState()

function make(id: string, overrides: Partial<AppNotification> = {}): AppNotification {
  return {
    id,
    type: 'NEW_MESSAGES',
    title: 'Alice',
    body: '1 new message',
    conversationId: 'c1',
    count: 1,
    read: false,
    createdAt: '2026-11-04T10:00:00Z',
    updatedAt: '2026-11-04T10:00:00Z',
    ...overrides,
  }
}

function page(items: AppNotification[], pageNumber: number, hasMore: boolean): NotificationPage {
  return { items, page: pageNumber, size: 20, totalItems: 40, hasMore }
}

function liveEvent(notification: AppNotification) {
  return { type: 'NOTIFICATION', eventId: 'e', conversationId: notification.conversationId, data: notification, timestamp: '' }
}

// A fake connection: tests call the handlers the way the WebSocket would.
function fakeConnection() {
  const handlers = new Map<string, (event: unknown) => void>()
  let connected: () => void = () => undefined
  const unsubscribe = vi.fn()
  const connection: NotificationConnection = {
    subscribe: (destination, handler) => {
      handlers.set(destination, handler)
      return () => {
        handlers.delete(destination)
        unsubscribe()
      }
    },
    onConnected: (listener) => {
      connected = listener
      return unsubscribe
    },
  }
  return { connection, handlers, fireConnected: () => connected(), unsubscribe }
}

beforeEach(() => {
  vi.useFakeTimers()
  vi.resetAllMocks()
  api.getUnreadCount.mockResolvedValue(0)
  api.markNotificationRead.mockResolvedValue(make('x', { read: true }))
  api.markAllNotificationsRead.mockResolvedValue(0)
  store().reset()
})

afterEach(() => {
  vi.useRealTimers()
})

describe('the unread count', () => {
  it('is read from the server', async () => {
    api.getUnreadCount.mockResolvedValue(7)
    await refreshUnreadCount()
    expect(store().unreadCount).toBe(7)
  })

  it('keeps the old number when the server cannot be reached', async () => {
    store().setUnreadCount(3)
    api.getUnreadCount.mockRejectedValue(new Error('down'))
    await refreshUnreadCount()
    expect(store().unreadCount).toBe(3)
  })

  it('is asked again 3 and 8 seconds after a chat was opened, and not when the chat was closed before', async () => {
    api.getUnreadCount.mockResolvedValue(4)
    const cancel = refreshUnreadCountAfterOpeningChat()

    await vi.advanceTimersByTimeAsync(2999)
    expect(api.getUnreadCount).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1)
    expect(api.getUnreadCount).toHaveBeenCalledTimes(1)
    await vi.advanceTimersByTimeAsync(5000)
    expect(api.getUnreadCount).toHaveBeenCalledTimes(2)
    expect(store().unreadCount).toBe(4)

    api.getUnreadCount.mockClear()
    const cancelSecond = refreshUnreadCountAfterOpeningChat()
    cancelSecond()
    await vi.advanceTimersByTimeAsync(10_000)
    expect(api.getUnreadCount).not.toHaveBeenCalled()
    cancel()
  })
})

describe('the list', () => {
  it('loads the first page, then more pages', async () => {
    api.getNotifications.mockResolvedValueOnce(page([make('a')], 0, true)).mockResolvedValueOnce(page([make('b', { updatedAt: '2026-11-04T09:00:00Z' })], 1, false))

    await loadFirstNotifications()
    expect(api.getNotifications).toHaveBeenLastCalledWith(0, 20)
    expect(store().hasMore).toBe(true)

    await loadMoreNotifications()
    expect(api.getNotifications).toHaveBeenLastCalledWith(1, 20)
    expect(store().items.map((item) => item.id)).toEqual(['a', 'b'])
    expect(store().hasMore).toBe(false)
  })

  it('does not load more when there is no more, and ignores a second request while one is running', async () => {
    await loadMoreNotifications()
    expect(api.getNotifications).not.toHaveBeenCalled()

    let resolve: (value: NotificationPage) => void = () => undefined
    api.getNotifications.mockReturnValue(new Promise((done) => (resolve = done)))
    const first = loadFirstNotifications()
    await loadFirstNotifications() // ignored
    expect(api.getNotifications).toHaveBeenCalledTimes(1)
    resolve(page([], 0, false))
    await first
  })

  it('a failed load can be tried again', async () => {
    api.getNotifications.mockRejectedValueOnce(new Error('down')).mockResolvedValueOnce(page([make('a')], 0, false))

    await expect(loadFirstNotifications()).rejects.toThrow('down')
    expect(store().loading).toBe(false)
    await loadFirstNotifications()
    expect(store().items).toHaveLength(1)
  })
})

describe('opening and reading', () => {
  it('opening an unread notification marks it read at once (badge drops), tells the server and returns the chat path', async () => {
    store().setUnreadCount(1)
    store().setFirstPage(page([make('a', { conversationId: 'c9' })], 0, false))

    const path = await openNotification(make('a', { conversationId: 'c9' }))

    expect(path).toBe('/chats/c9')
    expect(api.markNotificationRead).toHaveBeenCalledWith('a')
    expect(store().unreadCount).toBe(0)
    expect(store().items[0].read).toBe(true)
  })

  it('an already read notification is not marked again', async () => {
    const path = await openNotification(make('a', { read: true, conversationId: 'c9' }))

    expect(path).toBe('/chats/c9')
    expect(api.markNotificationRead).not.toHaveBeenCalled()
  })

  it('a missed call goes to the Calls page', async () => {
    expect(await openNotification(make('a', { conversationId: null, type: 'MISSED_CALL' }))).toBe('/calls')
  })

  it('a notification without a chat still leads somewhere: the chat list (a click is never "nothing happens")', async () => {
    expect(await openNotification(make('a', { conversationId: null, type: 'GROUP_REMOVED' }))).toBe('/chats')
  })

  it('asks the server for the true state when marking as read fails', async () => {
    store().setFirstPage(page([make('a')], 0, false))
    store().setUnreadCount(1)
    api.markNotificationRead.mockRejectedValue(new Error('down'))
    api.getUnreadCount.mockResolvedValue(1)
    api.getNotifications.mockResolvedValue(page([make('a')], 0, false))

    await openNotification(make('a'))
    await vi.advanceTimersByTimeAsync(0)

    expect(api.getUnreadCount).toHaveBeenCalled()
    expect(api.getNotifications).toHaveBeenCalled()
  })

  it('"mark all as read" clears the badge at once and tells the server', async () => {
    store().setUnreadCount(2)
    store().setFirstPage(page([make('a'), make('b')], 0, false))

    await markAllNotificationsRead()

    expect(store().unreadCount).toBe(0)
    expect(api.markAllNotificationsRead).toHaveBeenCalledTimes(1)
  })
})

describe('live events', () => {
  it('a NOTIFICATION event raises the badge at once, then the server number is read once for a burst', async () => {
    api.getUnreadCount.mockResolvedValue(2)

    handleNotificationEvent(liveEvent(make('a')))
    handleNotificationEvent(liveEvent(make('b', { type: 'GROUP_ADDED', conversationId: 'g1' })))

    expect(store().unreadCount).toBe(2) // our own guess, immediately
    expect(store().items).toHaveLength(2)
    await vi.advanceTimersByTimeAsync(600)
    expect(api.getUnreadCount).toHaveBeenCalledTimes(1) // one request for both events
    expect(store().unreadCount).toBe(2)
  })

  it('the same id again replaces the entry (count grows in place)', () => {
    handleNotificationEvent(liveEvent(make('a', { count: 1 })))
    handleNotificationEvent(liveEvent(make('a', { count: 3, body: '3 new messages', updatedAt: '2026-11-04T11:00:00Z' })))

    expect(store().items).toHaveLength(1)
    expect(store().items[0].count).toBe(3)
    expect(store().unreadCount).toBe(1)
  })

  it('ignores events that are not notifications or are broken', () => {
    handleNotificationEvent(null)
    handleNotificationEvent('text')
    handleNotificationEvent({ type: 'TYPING' })
    handleNotificationEvent({ type: 'NOTIFICATION' })
    handleNotificationEvent({ type: 'NOTIFICATION', data: { title: 'no id' } })

    expect(store().items).toEqual([])
    expect(store().unreadCount).toBe(0)
  })
})

describe('startNotifications', () => {
  it('reads the count right away and subscribes to the personal notification queue', async () => {
    api.getUnreadCount.mockResolvedValue(5)
    const live = fakeConnection()

    const stop = startNotifications(live.connection)
    await vi.advanceTimersByTimeAsync(0)

    expect(store().unreadCount).toBe(5)
    expect([...live.handlers.keys()]).toEqual(['/user/queue/notifications'])
    stop()
  })

  it('shows what arrives on the queue', () => {
    const live = fakeConnection()
    const stop = startNotifications(live.connection)

    live.handlers.get('/user/queue/notifications')?.(liveEvent(make('a')))

    expect(store().items).toHaveLength(1)
    stop()
  })

  it('reads the count again after every (re)connect, because events may have been missed', async () => {
    const live = fakeConnection()
    const stop = startNotifications(live.connection)
    await vi.advanceTimersByTimeAsync(0)
    api.getUnreadCount.mockClear()
    api.getUnreadCount.mockResolvedValue(9)

    live.fireConnected()
    await vi.advanceTimersByTimeAsync(0)

    expect(api.getUnreadCount).toHaveBeenCalledTimes(1)
    expect(store().unreadCount).toBe(9)
    stop()
  })

  it('stopping removes the subscription and the pending reconcile request', async () => {
    const live = fakeConnection()
    const stop = startNotifications(live.connection)
    await vi.advanceTimersByTimeAsync(0)
    api.getUnreadCount.mockClear()
    handleNotificationEvent(liveEvent(make('a')))

    stop()
    await vi.advanceTimersByTimeAsync(2000)

    expect(live.handlers.size).toBe(0)
    expect(api.getUnreadCount).not.toHaveBeenCalled()
  })
})
