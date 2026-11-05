import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AppError } from '../../services/api/appError'
import { getNotificationPreferences, updateNotificationPreferences } from '../../services/api/notificationApi'
import { renderWithProviders } from '../../test/renderWithProviders'
import type { NotificationPreferences } from '../../types/notification'
import NotificationsSection from './NotificationsSection'

vi.mock('../../services/api/notificationApi')
const mockedGet = vi.mocked(getNotificationPreferences)
const mockedUpdate = vi.mocked(updateNotificationPreferences)

const allOn: NotificationPreferences = { pushEnabled: true, messagesEnabled: true, groupsEnabled: true, callsEnabled: true }

// The browser's Notification class, replaced by a small fake for each test.
function fakeBrowserNotification(permission: NotificationPermission, requestResult: NotificationPermission = 'granted') {
  const requestPermission = vi.fn(async () => {
    fakeClass.permission = requestResult
    return requestResult
  })
  const fakeClass = { permission, requestPermission }
  vi.stubGlobal('Notification', fakeClass)
  return requestPermission
}

beforeEach(() => {
  vi.resetAllMocks()
  mockedGet.mockResolvedValue(allOn)
  fakeBrowserNotification('default')
})

afterEach(() => {
  vi.unstubAllGlobals()
})

describe('NotificationsSection: server preferences', () => {
  it('shows the saved preferences', async () => {
    mockedGet.mockResolvedValue({ ...allOn, groupsEnabled: false })
    renderWithProviders(<NotificationsSection />)

    expect(await screen.findByLabelText(/Push notifications/)).toBeChecked()
    expect(screen.getByLabelText(/New messages in private chats/)).toBeChecked()
    expect(screen.getByLabelText(/^Groups/)).not.toBeChecked()
    expect(screen.getByLabelText(/^Missed calls/)).toBeChecked()
    expect(screen.getByText('A notification when you miss a voice or video call.')).toBeInTheDocument()
  })

  it('saves a changed switch with the other values unchanged, and moves the switch at once', async () => {
    let finish: (value: NotificationPreferences) => void = () => undefined
    mockedUpdate.mockImplementation(() => new Promise((resolve) => (finish = resolve)))
    renderWithProviders(<NotificationsSection />)
    const user = userEvent.setup()

    const push = await screen.findByLabelText(/Push notifications/)
    await user.click(push)

    // optimistic: switched off before the server answered
    expect(push).not.toBeChecked()
    expect(mockedUpdate).toHaveBeenCalledWith({ ...allOn, pushEnabled: false })

    finish({ ...allOn, pushEnabled: false })
    await waitFor(() => expect(push).not.toBeChecked())
  })

  it('moves the switch back and shows the problem when the server refuses', async () => {
    mockedUpdate.mockRejectedValue(new AppError('SERVER', 'Something went wrong. Please try again later.', 500))
    renderWithProviders(<NotificationsSection />)
    const user = userEvent.setup()

    const groups = await screen.findByLabelText(/^Groups/)
    await user.click(groups)

    expect(await screen.findByRole('alert')).toHaveTextContent('Something went wrong')
    await waitFor(() => expect(groups).toBeChecked()) // rolled back
  })

  it('shows a loading error', async () => {
    mockedGet.mockRejectedValue(new AppError('NETWORK', 'Cannot reach the server.', 0))
    renderWithProviders(<NotificationsSection />)

    expect(await screen.findByRole('alert')).toHaveTextContent('Cannot reach the server.')
  })
})

describe('NotificationsSection: desktop notifications on this device', () => {
  it('asks the browser for permission when switched on, and remembers the choice', async () => {
    const requestPermission = fakeBrowserNotification('default', 'granted')
    renderWithProviders(<NotificationsSection />)
    const user = userEvent.setup()

    const toggle = await screen.findByLabelText(/Show a pop-up for new messages/)
    expect(toggle).not.toBeChecked()
    await user.click(toggle)

    expect(requestPermission).toHaveBeenCalledTimes(1)
    await waitFor(() => expect(toggle).toBeChecked())
    expect(localStorage.getItem('chat-app-desktop-notifications')).toBe('true')
  })

  it('stays off with a plain explanation when the user says no to the browser question', async () => {
    fakeBrowserNotification('default', 'denied')
    renderWithProviders(<NotificationsSection />)
    const user = userEvent.setup()

    const toggle = await screen.findByLabelText(/Show a pop-up for new messages/)
    await user.click(toggle)

    expect(await screen.findByText(/were not allowed, so they stay off/)).toBeInTheDocument()
    expect(toggle).not.toBeChecked()
    expect(localStorage.getItem('chat-app-desktop-notifications')).toBe('false')
  })

  it('is on when it was chosen before and the browser allows it, and can be switched off without asking again', async () => {
    const requestPermission = fakeBrowserNotification('granted')
    localStorage.setItem('chat-app-desktop-notifications', 'true')
    renderWithProviders(<NotificationsSection />)
    const user = userEvent.setup()

    const toggle = await screen.findByLabelText(/Show a pop-up for new messages/)
    expect(toggle).toBeChecked()
    await user.click(toggle)

    expect(toggle).not.toBeChecked()
    expect(localStorage.getItem('chat-app-desktop-notifications')).toBe('false')
    expect(requestPermission).not.toHaveBeenCalled()
  })

  it('is off (even if chosen) when the browser permission is missing', async () => {
    fakeBrowserNotification('default')
    localStorage.setItem('chat-app-desktop-notifications', 'true')
    renderWithProviders(<NotificationsSection />)

    expect(await screen.findByLabelText(/Show a pop-up for new messages/)).not.toBeChecked()
  })

  it('explains and disables the switch when the browser blocked notifications for this site', async () => {
    fakeBrowserNotification('denied')
    renderWithProviders(<NotificationsSection />)

    const toggle = await screen.findByLabelText(/Show a pop-up for new messages/)
    expect(toggle).toBeDisabled()
    expect(screen.getByText(/blocked for this site in your browser settings/)).toBeInTheDocument()
  })

  it('explains and disables the switch when the browser does not support them', async () => {
    vi.unstubAllGlobals() // no Notification class at all (jsdom has none)
    renderWithProviders(<NotificationsSection />)

    const toggle = await screen.findByLabelText(/Show a pop-up for new messages/)
    expect(toggle).toBeDisabled()
    expect(screen.getByText('This browser does not support desktop notifications.')).toBeInTheDocument()
  })
})
