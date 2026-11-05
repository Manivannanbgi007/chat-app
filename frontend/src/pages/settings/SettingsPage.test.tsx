import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { deleteSession, getSessions } from '../../services/api/authApi'
import { getNotificationPreferences } from '../../services/api/notificationApi'
import { getStatusPrivacy } from '../../services/api/statusApi'
import { getPrivacy, updatePrivacy } from '../../services/api/userApi'
import { useAuthStore } from '../../store/authStore'
import { fakeTokens, renderWithProviders } from '../../test/renderWithProviders'
import type { SessionInfo } from '../../types/auth'
import type { Privacy } from '../../types/user'
import SettingsPage from './SettingsPage'

vi.mock('../../services/api/authApi')
vi.mock('../../services/api/userApi')
vi.mock('../../services/api/notificationApi')
vi.mock('../../services/api/statusApi')
const mockedGetPrivacy = vi.mocked(getPrivacy)
const mockedUpdatePrivacy = vi.mocked(updatePrivacy)
const mockedGetSessions = vi.mocked(getSessions)
const mockedDeleteSession = vi.mocked(deleteSession)

const privacy: Privacy = { lastSeen: 'EVERYONE', profilePhoto: 'EVERYONE', about: 'CONTACTS', groupAdd: 'EVERYONE', readReceipts: true }
const sessions: SessionInfo[] = [
  { id: 's1', deviceName: 'Chrome on Windows', ipAddress: '10.0.0.5', createdAt: '2026-11-01T10:00:00Z', lastActiveAt: '2026-11-04T09:00:00Z', current: true },
  { id: 's2', deviceName: 'Safari on iOS', ipAddress: null, createdAt: '2026-11-02T10:00:00Z', lastActiveAt: '2026-11-03T09:00:00Z', current: false },
]

function renderSettings() {
  renderWithProviders(
    <Routes>
      <Route path="/settings" element={<SettingsPage />} />
    </Routes>,
    { route: '/settings' },
  )
}

describe('SettingsPage', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    mockedGetPrivacy.mockResolvedValue(privacy)
    vi.mocked(getStatusPrivacy).mockResolvedValue({ mode: 'CONTACTS', userIds: [] })
    mockedGetSessions.mockResolvedValue(sessions)
    vi.mocked(getNotificationPreferences).mockResolvedValue({
      pushEnabled: true, messagesEnabled: true, groupsEnabled: true, callsEnabled: true,
    })
    useAuthStore.getState().setSession(fakeTokens(), false)
  })

  it('saves the changed privacy settings', async () => {
    mockedUpdatePrivacy.mockImplementation(async (body) => body)
    renderSettings()
    const user = userEvent.setup()

    const lastSeen = await screen.findByLabelText('Who can see my last seen')
    expect(screen.getByLabelText('Who can see my about')).toHaveValue('CONTACTS') // loaded from the server
    await user.selectOptions(lastSeen, 'NOBODY')
    await user.click(screen.getByLabelText('Send read receipts (blue ticks)'))
    await user.click(screen.getByRole('button', { name: 'Save privacy settings' }))

    expect(await screen.findByText('Privacy settings saved.')).toBeInTheDocument()
    expect(mockedUpdatePrivacy).toHaveBeenCalledWith({ ...privacy, lastSeen: 'NOBODY', readReceipts: false })
  })

  it('lists the active devices and marks the current one', async () => {
    renderSettings()

    expect(await screen.findByText('Chrome on Windows')).toBeInTheDocument()
    expect(screen.getByText('Safari on iOS')).toBeInTheDocument()
    expect(screen.getAllByText('This device')).toHaveLength(1)
  })

  it('ends another device session and stays logged in', async () => {
    mockedDeleteSession.mockResolvedValue()
    renderSettings()
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'End session' }))

    expect(mockedDeleteSession).toHaveBeenCalledWith('s2')
    expect(useAuthStore.getState().accessToken).toBe('access-1')
  })

  it('ending the current device logs this browser out', async () => {
    mockedDeleteSession.mockResolvedValue()
    renderSettings()
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Log out this device' }))

    expect(mockedDeleteSession).toHaveBeenCalledWith('s1')
    await vi.waitFor(() => expect(useAuthStore.getState().accessToken).toBeNull())
  })

  it('shows the notification settings (details in NotificationsSection.test.tsx)', async () => {
    renderSettings()
    expect(await screen.findByLabelText(/Push notifications/)).toBeChecked()
    expect(screen.getByText('Desktop notifications on this device')).toBeInTheDocument()
  })
})
