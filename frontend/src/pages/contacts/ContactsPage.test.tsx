import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { startConversation } from '../../features/messaging/chatActions'
import { addContact, blockUser, getBlockedUsers, getContacts, getUserProfile, searchUsers } from '../../services/api/userApi'
import LocationDisplay from '../../test/LocationDisplay'
import { renderWithProviders } from '../../test/renderWithProviders'
import type { Contact, PublicProfile } from '../../types/user'
import ContactsPage from './ContactsPage'

vi.mock('../../services/api/userApi')
vi.mock('../../features/messaging/chatActions')
const mocked = {
  getContacts: vi.mocked(getContacts),
  searchUsers: vi.mocked(searchUsers),
  addContact: vi.mocked(addContact),
  blockUser: vi.mocked(blockUser),
  getBlockedUsers: vi.mocked(getBlockedUsers),
  getUserProfile: vi.mocked(getUserProfile),
}

const ravi: PublicProfile = { id: 'u-ravi', username: 'ravi', displayName: 'Ravi Kumar', profileImageUrl: null, about: 'Hello' }
const meena: PublicProfile = { id: 'u-meena', username: 'meena', displayName: 'Meena Iyer', profileImageUrl: null, about: null }
const existingContact: Contact = { id: 'c1', contactUserId: meena.id, nickname: null, createdAt: '2026-01-01T00:00:00Z', user: meena }

function renderPage() {
  renderWithProviders(
    <Routes>
      <Route path="/contacts" element={<ContactsPage />} />
      <Route path="/chats/:id" element={<LocationDisplay />} />
    </Routes>,
    { route: '/contacts' },
  )
}

describe('ContactsPage', () => {
  beforeEach(() => {
    Object.values(mocked).forEach((fn) => fn.mockReset())
    mocked.getContacts.mockResolvedValue([existingContact])
    mocked.getBlockedUsers.mockResolvedValue([])
  })

  it('lists my contacts with an enabled "Start a conversation" button', async () => {
    renderPage()

    expect(await screen.findByText('Meena Iyer')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Start a conversation' })).toBeEnabled()
  })

  it('"Start a conversation" opens the chat with that person', async () => {
    vi.mocked(startConversation).mockResolvedValue('conv-42')
    renderPage()
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Start a conversation' }))

    expect(startConversation).toHaveBeenCalledWith(meena.id)
    expect(await screen.findByTestId('path')).toHaveTextContent('/chats/conv-42')
  })

  it('shows the reason when the conversation cannot be started (for example blocked)', async () => {
    vi.mocked(startConversation).mockRejectedValue(new Error('boom'))
    renderPage()
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'Start a conversation' }))

    expect(await screen.findByRole('alert')).toBeInTheDocument()
  })

  it('searches after typing (debounced), shows who is already a contact, and adds a new one', async () => {
    mocked.searchUsers.mockResolvedValue([ravi, meena])
    mocked.addContact.mockResolvedValue({ ...existingContact, id: 'c2', contactUserId: ravi.id, user: ravi })
    renderPage()
    const user = userEvent.setup()

    await user.click(screen.getByRole('tab', { name: 'Find people' }))
    await user.type(screen.getByLabelText('Search people'), 'r')
    expect(mocked.searchUsers).not.toHaveBeenCalled() // fewer than 2 characters

    await user.type(screen.getByLabelText('Search people'), 'a')
    expect(await screen.findByText('Ravi Kumar')).toBeInTheDocument() // appears after the 400 ms pause
    expect(mocked.searchUsers).toHaveBeenCalledTimes(1)
    expect(mocked.searchUsers).toHaveBeenCalledWith('ra')

    expect(screen.getByText('In contacts')).toBeInTheDocument() // Meena
    await user.click(screen.getByRole('button', { name: 'Add contact' })) // only Ravi has the button

    expect(mocked.addContact).toHaveBeenCalledWith({ contactUserId: ravi.id })
  })

  it('blocks a person from the search results', async () => {
    mocked.searchUsers.mockResolvedValue([ravi])
    mocked.blockUser.mockResolvedValue()
    renderPage()
    const user = userEvent.setup()

    await user.click(screen.getByRole('tab', { name: 'Find people' }))
    await user.type(screen.getByLabelText('Search people'), 'rav')
    await user.click(await screen.findByRole('button', { name: 'Block' }))

    expect(mocked.blockUser).toHaveBeenCalledWith(ravi.id)
  })

  it('shows another user profile, handling a hidden "about"', async () => {
    mocked.getUserProfile.mockResolvedValue(meena)
    renderPage()
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: 'View profile of Meena Iyer' }))

    expect(await screen.findByRole('dialog')).toHaveTextContent('@meena')
    expect(screen.getByText('No about info to show.')).toBeInTheDocument()
  })
})
