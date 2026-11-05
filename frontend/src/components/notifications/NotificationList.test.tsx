import { act, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import * as notificationApi from '../../services/api/notificationApi'
import { useNotificationStore } from '../../store/notificationStore'
import { renderWithProviders } from '../../test/renderWithProviders'
import type { AppNotification, NotificationPage } from '../../types/notification'
import NotificationList from './NotificationList'

vi.mock('../../services/api/notificationApi')
const api = vi.mocked(notificationApi)
const store = () => useNotificationStore.getState()

function make(id: string, overrides: Partial<AppNotification> = {}): AppNotification {
  return {
    id,
    type: 'NEW_MESSAGES',
    title: `Title ${id}`,
    body: `Body ${id}`,
    conversationId: `chat-${id}`,
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

function renderList(onNavigate?: () => void) {
  return renderWithProviders(
    <Routes>
      <Route path="/" element={<NotificationList onNavigate={onNavigate} />} />
      <Route path="/chats/:id" element={<p>Chat page</p>} />
      <Route path="/calls" element={<p>Calls page</p>} />
    </Routes>,
  )
}

beforeEach(() => {
  vi.resetAllMocks()
  api.markNotificationRead.mockResolvedValue(make('x', { read: true }))
  api.markAllNotificationsRead.mockResolvedValue(0)
  api.getUnreadCount.mockResolvedValue(0)
  store().reset()
})

describe('NotificationList', () => {
  it('shows the entries with title, text, a relative time and a dot only for unread ones', async () => {
    api.getNotifications.mockResolvedValue(
      page([make('a'), make('b', { read: true, updatedAt: '2026-11-04T09:00:00Z' })], 0, false),
    )
    store().setUnreadCount(1)
    renderList()

    expect(await screen.findByText('Title a')).toBeInTheDocument()
    expect(screen.getByText('Body a')).toBeInTheDocument()
    const rows = within(screen.getByRole('list', { name: 'Notifications' })).getAllByRole('listitem')
    expect(rows).toHaveLength(2)
    expect(within(rows[0]).getByRole('img', { name: 'Unread' })).toBeInTheDocument()
    expect(within(rows[1]).queryByRole('img', { name: 'Unread' })).not.toBeInTheDocument()
  })

  it('says so when there is nothing yet', async () => {
    api.getNotifications.mockResolvedValue(page([], 0, false))
    renderList()

    // ONE message only: "all caught up". No second line such as "Nothing new", no "Mark all as read" with nothing to mark.
    expect(await screen.findByText('You are all caught up')).toBeInTheDocument()
    expect(screen.queryByText('Nothing new')).not.toBeInTheDocument()
    expect(screen.queryByText(/turned off/)).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Mark all as read' })).not.toBeInTheDocument()
  })

  it('when every kind of notification is switched off, an empty list says ONLY that, with a way to turn them on', async () => {
    const user = userEvent.setup()
    api.getNotifications.mockResolvedValue(page([], 0, false))
    store().setPreferences({ pushEnabled: true, messagesEnabled: false, groupsEnabled: false, callsEnabled: false })
    renderWithProviders(
      <Routes>
        <Route path="/" element={<NotificationList />} />
        <Route path="/settings" element={<p>Settings page</p>} />
      </Routes>,
    )

    expect(await screen.findByText('Notifications are turned off')).toBeInTheDocument()
    expect(screen.queryByText('You are all caught up')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Turn them on' }))
    expect(await screen.findByText('Settings page')).toBeInTheDocument()
  })

  it('with only some kinds switched off the normal empty message is shown', async () => {
    api.getNotifications.mockResolvedValue(page([], 0, false))
    store().setPreferences({ pushEnabled: true, messagesEnabled: false, groupsEnabled: true, callsEnabled: true })
    renderList()

    expect(await screen.findByText('You are all caught up')).toBeInTheDocument()
    expect(screen.queryByText('Notifications are turned off')).not.toBeInTheDocument()
  })

  it('shows "New" above "Earlier" when there are both, and one line of text per notification', async () => {
    api.getNotifications.mockResolvedValue(page([make('a'), make('b', { read: true, updatedAt: '2026-11-04T09:00:00Z' })], 0, false))
    store().setUnreadCount(1)
    renderList()

    await screen.findByText('Title a')
    expect(screen.getByText('New')).toBeInTheDocument()
    expect(screen.getByText('Earlier')).toBeInTheDocument()
    expect(screen.getAllByText(/^Body /)).toHaveLength(2) // exactly one text line per notification
  })

  it('a notification without a chat opens the chat list instead of doing nothing', async () => {
    const user = userEvent.setup()
    api.getNotifications.mockResolvedValue(page([make('a', { conversationId: null, type: 'GROUP_REMOVED' })], 0, false))
    store().setUnreadCount(1)
    renderWithProviders(
      <Routes>
        <Route path="/" element={<NotificationList />} />
        <Route path="/chats" element={<p>Chat list page</p>} />
      </Routes>,
    )

    await user.click(await screen.findByRole('button', { name: /Title a/ }))

    expect(await screen.findByText('Chat list page')).toBeInTheDocument()
  })

  it('"Load more" loads the next page and disappears at the end', async () => {
    const user = userEvent.setup()
    api.getNotifications
      .mockResolvedValueOnce(page([make('a', { updatedAt: '2026-11-04T10:00:00Z' })], 0, true))
      .mockResolvedValueOnce(page([make('b', { updatedAt: '2026-11-04T09:00:00Z' })], 1, false))
    renderList()
    await screen.findByText('Title a')

    await user.click(screen.getByRole('button', { name: 'Load more' }))

    expect(await screen.findByText('Title b')).toBeInTheDocument()
    expect(api.getNotifications).toHaveBeenLastCalledWith(1, 20)
    expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument()
  })

  it('shows a readable message when loading fails', async () => {
    api.getNotifications.mockRejectedValue(new Error('boom'))
    renderList()

    expect(await screen.findByRole('alert')).toBeInTheDocument()
  })

  it('a click marks the notification read, tells the server, closes the panel and opens the chat', async () => {
    const user = userEvent.setup()
    const onNavigate = vi.fn()
    api.getNotifications.mockResolvedValue(page([make('a')], 0, false))
    store().setUnreadCount(1)
    renderList(onNavigate)

    await user.click(await screen.findByRole('button', { name: /Title a/ }))

    expect(api.markNotificationRead).toHaveBeenCalledWith('a')
    expect(onNavigate).toHaveBeenCalledTimes(1)
    expect(await screen.findByText('Chat page')).toBeInTheDocument()
    expect(store().unreadCount).toBe(0)
  })

  it('a read notification opens the chat without asking the server again', async () => {
    const user = userEvent.setup()
    api.getNotifications.mockResolvedValue(page([make('a', { read: true })], 0, false))
    renderList()

    await user.click(await screen.findByRole('button', { name: /Title a/ }))

    expect(await screen.findByText('Chat page')).toBeInTheDocument()
    expect(api.markNotificationRead).not.toHaveBeenCalled()
  })

  it('a notification without a chat (you were removed) is marked read but goes nowhere', async () => {
    const user = userEvent.setup()
    api.getNotifications.mockResolvedValue(page([make('a', { type: 'GROUP_REMOVED', conversationId: null })], 0, false))
    store().setUnreadCount(1)
    renderList()

    await user.click(await screen.findByRole('button', { name: /Title a/ }))

    expect(api.markNotificationRead).toHaveBeenCalledWith('a')
    expect(screen.queryByText('Chat page')).not.toBeInTheDocument()
  })

  it('a missed call opens the Calls page', async () => {
    const user = userEvent.setup()
    api.getNotifications.mockResolvedValue(
      page([make('a', { type: 'MISSED_CALL', conversationId: null, body: 'Missed video call' })], 0, false),
    )
    renderList()

    await user.click(await screen.findByRole('button', { name: /Missed video call/ }))

    expect(await screen.findByText('Calls page')).toBeInTheDocument()
  })

  it('"Mark all as read" clears the unread state and tells the server once', async () => {
    const user = userEvent.setup()
    api.getNotifications.mockResolvedValue(page([make('a'), make('b')], 0, false))
    store().setUnreadCount(2)
    renderList()
    await screen.findByText('Title a')
    expect(screen.getByText('2 unread')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Mark all as read' }))

    expect(api.markAllNotificationsRead).toHaveBeenCalledTimes(1)
    expect(screen.getByText('Nothing new')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Mark all as read' })).not.toBeInTheDocument()
    expect(screen.queryAllByRole('img', { name: 'Unread' })).toHaveLength(0)
  })

  it('a live update with the same id replaces the entry instead of adding a second one', async () => {
    api.getNotifications.mockResolvedValue(page([make('a', { body: '1 new message', count: 1 })], 0, false))
    store().setUnreadCount(1)
    renderList()
    await screen.findByText('1 new message')

    act(() => store().applyLive(make('a', { body: '3 new messages', count: 3, updatedAt: '2026-11-04T11:00:00Z' })))

    expect(screen.getByText('3 new messages')).toBeInTheDocument()
    expect(screen.queryByText('1 new message')).not.toBeInTheDocument()
    expect(screen.getAllByRole('listitem')).toHaveLength(1)
  })

  it('a brand new live notification appears at the top without reloading', async () => {
    api.getNotifications.mockResolvedValue(page([make('a', { updatedAt: '2026-11-04T09:00:00Z' })], 0, false))
    renderList()
    await screen.findByText('Title a')

    act(() => store().applyLive(make('b', { updatedAt: '2026-11-04T12:00:00Z' })))

    const rows = screen.getAllByRole('listitem')
    expect(within(rows[0]).getByText('Title b')).toBeInTheDocument()
  })
})
