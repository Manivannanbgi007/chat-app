import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { getStatusPrivacy, updateStatusPrivacy } from '../../services/api/statusApi'
import { getContacts } from '../../services/api/userApi'
import { profile } from '../../test/chatFixtures'
import { renderWithProviders } from '../../test/renderWithProviders'
import type { Contact } from '../../types/user'
import StatusPrivacySection from './StatusPrivacySection'

vi.mock('../../services/api/statusApi')
vi.mock('../../services/api/userApi')

const contacts: Contact[] = [
  { id: 'c1', contactUserId: 'u-ravi', nickname: null, createdAt: '2026-11-01T10:00:00Z', user: profile('u-ravi', 'Ravi Kumar') },
  { id: 'c2', contactUserId: 'u-meena', nickname: null, createdAt: '2026-11-01T10:00:00Z', user: profile('u-meena', 'Meena Iyer') },
]

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(getContacts).mockResolvedValue(contacts)
  vi.mocked(updateStatusPrivacy).mockImplementation(async (body) => body)
})

describe('StatusPrivacySection', () => {
  it('shows the saved mode and explains that changes apply at once', async () => {
    vi.mocked(getStatusPrivacy).mockResolvedValue({ mode: 'CONTACTS', userIds: [] })
    renderWithProviders(<StatusPrivacySection />)

    expect(await screen.findByRole('radio', { name: 'My contacts' })).toBeChecked()
    expect(screen.getByText(/also to status updates you already posted/)).toBeInTheDocument()
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument() // no picker for "My contacts"
  })

  it('"My contacts except..." with a chosen person saves that list', async () => {
    vi.mocked(getStatusPrivacy).mockResolvedValue({ mode: 'CONTACTS', userIds: [] })
    renderWithProviders(<StatusPrivacySection />)

    await userEvent.click(await screen.findByRole('radio', { name: 'My contacts except...' }))
    expect(screen.getByText('Hide my status from:')).toBeInTheDocument()
    await userEvent.click(await screen.findByRole('checkbox', { name: /Ravi Kumar/ }))
    await userEvent.click(screen.getByRole('button', { name: 'Save status privacy' }))

    await waitFor(() => expect(updateStatusPrivacy).toHaveBeenCalledWith({ mode: 'CONTACTS_EXCEPT', userIds: ['u-ravi'] }))
    expect(await screen.findByText('Status privacy saved.')).toBeInTheDocument()
  })

  it('keeps a separate list per mode and "My contacts" saves an empty list', async () => {
    vi.mocked(getStatusPrivacy).mockResolvedValue({ mode: 'ONLY_SHARE_WITH', userIds: ['u-meena'] })
    renderWithProviders(<StatusPrivacySection />)

    expect(await screen.findByRole('radio', { name: 'Only share with...' })).toBeChecked()
    expect(await screen.findByRole('checkbox', { name: /Meena Iyer/ })).toBeChecked()

    // the other list starts empty ...
    await userEvent.click(screen.getByRole('radio', { name: 'My contacts except...' }))
    expect(screen.getByRole('checkbox', { name: /Meena Iyer/ })).not.toBeChecked()
    // ... and the first one is still there when coming back
    await userEvent.click(screen.getByRole('radio', { name: 'Only share with...' }))
    expect(screen.getByRole('checkbox', { name: /Meena Iyer/ })).toBeChecked()

    await userEvent.click(screen.getByRole('radio', { name: 'My contacts' }))
    await userEvent.click(screen.getByRole('button', { name: 'Save status privacy' }))
    await waitFor(() => expect(updateStatusPrivacy).toHaveBeenCalledWith({ mode: 'CONTACTS', userIds: [] }))
  })

  it('warns that nobody will see the status when "Only share with..." has nobody chosen', async () => {
    vi.mocked(getStatusPrivacy).mockResolvedValue({ mode: 'CONTACTS', userIds: [] })
    renderWithProviders(<StatusPrivacySection />)
    await userEvent.click(await screen.findByRole('radio', { name: 'Only share with...' }))
    expect(screen.getByText(/nobody will see your status/)).toBeInTheDocument()
  })
})
