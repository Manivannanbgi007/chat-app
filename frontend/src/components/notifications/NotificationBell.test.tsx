import { act, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import * as notificationApi from '../../services/api/notificationApi'
import { useNotificationStore } from '../../store/notificationStore'
import { renderWithProviders } from '../../test/renderWithProviders'
import type { AppNotification } from '../../types/notification'
import { badgeText } from '../../features/notifications/badgeText'
import NotificationBell from './NotificationBell'

vi.mock('../../services/api/notificationApi')
const api = vi.mocked(notificationApi)
const store = () => useNotificationStore.getState()

function make(id: string, overrides: Partial<AppNotification> = {}): AppNotification {
  return {
    id,
    type: 'NEW_MESSAGES',
    title: 'Alice',
    body: '2 new messages',
    conversationId: 'c1',
    count: 2,
    read: false,
    createdAt: '2026-11-04T10:00:00Z',
    updatedAt: '2026-11-04T10:00:00Z',
    ...overrides,
  }
}

function renderBell() {
  return renderWithProviders(
    <Routes>
      <Route path="/chats" element={<NotificationBell />} />
      <Route path="/notifications" element={<p>Notifications page</p>} />
    </Routes>,
    { route: '/chats' },
  )
}

function setPhone(isPhone: boolean) {
  vi.stubGlobal('matchMedia', (query: string) => ({ matches: isPhone, media: query, addEventListener: vi.fn(), removeEventListener: vi.fn() }))
}

beforeEach(() => {
  vi.resetAllMocks()
  api.getNotifications.mockResolvedValue({ items: [], page: 0, size: 20, totalItems: 0, hasMore: false })
  store().reset()
  setPhone(false)
})

afterEach(() => vi.unstubAllGlobals())

describe('badgeText', () => {
  it('shows the number up to 99 and "99+" above', () => {
    expect(badgeText(1)).toBe('1')
    expect(badgeText(99)).toBe('99')
    expect(badgeText(100)).toBe('99+')
    expect(badgeText(5000)).toBe('99+')
  })
})

describe('NotificationBell badge', () => {
  it('shows no badge when nothing is unread', () => {
    renderBell()

    expect(screen.queryByTestId('bell-badge')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Notifications' })).toBeInTheDocument()
  })

  it('shows the unread number, also in the accessible name', () => {
    store().setUnreadCount(3)
    renderBell()

    expect(screen.getByTestId('bell-badge')).toHaveTextContent('3')
    expect(screen.getByRole('button', { name: 'Notifications, 3 unread' })).toBeInTheDocument()
  })

  it('caps the badge at 99+', () => {
    store().setUnreadCount(250)
    renderBell()

    expect(screen.getByTestId('bell-badge')).toHaveTextContent('99+')
  })

  it('grows live when a notification arrives, and disappears when everything is read', () => {
    renderBell()

    act(() => store().applyLive(make('a', { count: 1 })))
    expect(screen.getByTestId('bell-badge')).toHaveTextContent('1')

    act(() => store().applyLive(make('b', { conversationId: 'c2', count: 1 })))
    expect(screen.getByTestId('bell-badge')).toHaveTextContent('2')

    act(() => store().markAllReadLocal())
    expect(screen.queryByTestId('bell-badge')).not.toBeInTheDocument()
  })

  it('an updated notification (same id) does not raise the badge a second time', () => {
    renderBell()

    act(() => store().applyLive(make('a', { count: 1 })))
    act(() => store().applyLive(make('a', { count: 2, updatedAt: '2026-11-04T10:05:00Z' })))

    expect(screen.getByTestId('bell-badge')).toHaveTextContent('1')
  })
})

describe('NotificationBell panel', () => {
  it('opens a panel with the list on a big screen, and closes it with Escape', async () => {
    const user = userEvent.setup()
    api.getNotifications.mockResolvedValue({ items: [make('a')], page: 0, size: 20, totalItems: 1, hasMore: false })
    renderBell()

    await user.click(screen.getByRole('button', { name: 'Notifications' }))

    expect(screen.getByRole('dialog', { name: 'Notifications panel' })).toBeInTheDocument()
    expect(await screen.findByText('2 new messages')).toBeInTheDocument()

    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('closes when the user clicks somewhere else', async () => {
    const user = userEvent.setup()
    renderBell()
    await user.click(screen.getByRole('button', { name: 'Notifications' }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()

    await user.click(document.body)

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('on a phone it opens the full /notifications page instead of a panel', async () => {
    setPhone(true)
    const user = userEvent.setup()
    renderBell()

    await user.click(screen.getByRole('button', { name: 'Notifications' }))

    await waitFor(() => expect(screen.getByText('Notifications page')).toBeInTheDocument())
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})
