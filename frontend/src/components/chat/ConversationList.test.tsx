import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import * as chatApi from '../../services/api/chatApi'
import { AppError } from '../../services/api/appError'
import { getContacts } from '../../services/api/userApi'
import { useAuthStore } from '../../store/authStore'
import { useChatStore } from '../../store/chatStore'
import { ME, OTHER, makeConversation, makeGroup, makeMediaMessage, makeMessage, makeSystemMessage, profile } from '../../test/chatFixtures'
import { fakeTokens, renderWithProviders } from '../../test/renderWithProviders'
import type { Conversation } from '../../types/chat'
import ConversationList from './ConversationList'

vi.mock('../../services/api/chatApi')
vi.mock('../../services/api/userApi')
const api = vi.mocked(chatApi)

function renderList(loadError: string | null = null) {
  renderWithProviders(
    <Routes>
      <Route path="/chats/*" element={<ConversationList loadError={loadError} />} />
    </Routes>,
    { route: '/chats' },
  )
}

function seed() {
  const ravi = makeConversation({ id: 'c-ravi', otherUser: profile('u-ravi', 'Ravi Kumar') })
  const meena = makeConversation({ id: 'c-meena', otherUser: profile('u-meena', 'Meena Iyer') })
  const quiet = makeConversation({ id: 'c-quiet', createdAt: '2026-11-02T00:00:00.000000Z', otherUser: profile('u-quiet', 'Quiet Person') })
  useChatStore.setState({
    conversationsLoaded: true,
    conversations: { 'c-ravi': ravi, 'c-meena': meena, 'c-quiet': quiet },
    summaries: {
      'c-ravi': {
        unreadCount: 3,
        lastMessage: makeMessage({ conversationId: 'c-ravi', content: 'Are you coming?', createdAt: '2026-11-04T09:00:00.000000Z' }),
      },
      'c-meena': {
        unreadCount: 0,
        lastMessage: makeMessage({
          conversationId: 'c-meena', senderId: ME, content: 'See you tomorrow', status: 'READ', createdAt: '2026-11-04T11:00:00.000000Z',
        }),
      },
    },
  })
}

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(getContacts).mockResolvedValue([])
  useChatStore.getState().reset()
  useAuthStore.setState({ ...useAuthStore.getState(), user: { ...fakeTokens().user, id: ME } })
})

describe('ConversationList', () => {
  it('lists conversations with the newest activity first', () => {
    seed()
    renderList()

    const names = within(screen.getByRole('list', { name: 'Conversations' }))
      .getAllByRole('link')
      .map((link) => link.textContent)

    expect(names[0]).toContain('Meena Iyer') // 11:00
    expect(names[1]).toContain('Ravi Kumar') // 09:00
    expect(names[2]).toContain('Quiet Person') // no messages
  })

  it('shows the unread badge only where there are unread messages', () => {
    seed()
    renderList()

    expect(screen.getByLabelText('3 unread messages')).toHaveTextContent('3')
    expect(screen.getAllByLabelText(/unread messages/)).toHaveLength(1)
  })

  it('shows the last message, and a tick for my own last message', () => {
    seed()
    renderList()

    expect(screen.getByText('Are you coming?')).toBeInTheDocument()
    expect(screen.getByText('See you tomorrow')).toBeInTheDocument()
    expect(screen.getByLabelText('Read')).toBeInTheDocument() // Meena's row: my message was read
    expect(screen.getByText('No messages yet')).toBeInTheDocument()
  })

  it('shows an icon and a label for the last message when it is a file', () => {
    useChatStore.setState({
      conversationsLoaded: true,
      conversations: {
        'c-photo': makeConversation({ id: 'c-photo' }),
        'c-voice': makeConversation({ id: 'c-voice', otherUser: profile('u-v', 'Vera Voice') }),
      },
      summaries: {
        'c-photo': {
          unreadCount: 0,
          lastMessage: makeMediaMessage({ kind: 'IMAGE' }, { conversationId: 'c-photo', content: 'Sunset', createdAt: '2026-11-04T09:00:00.000000Z' }),
        },
        'c-voice': {
          unreadCount: 0,
          lastMessage: makeMediaMessage({ kind: 'VOICE', hasThumbnail: false }, { conversationId: 'c-voice', createdAt: '2026-11-04T10:00:00.000000Z' }),
        },
      },
    })
    renderList()

    expect(screen.getByText('Photo: Sunset')).toBeInTheDocument()
    expect(screen.getByText('Voice message')).toBeInTheDocument()
    expect(document.querySelectorAll('svg.lucide-image, svg.lucide-mic')).toHaveLength(2) // an icon in front of each
  })

  it('links every row to its chat', () => {
    seed()
    renderList()

    expect(screen.getByRole('link', { name: /Ravi Kumar/ })).toHaveAttribute('href', '/chats/c-ravi')
  })

  it('explains an empty list', () => {
    useChatStore.setState({ conversationsLoaded: true })
    renderList()

    expect(screen.getByText(/No chats yet/)).toBeInTheDocument()
  })

  it('shows the loading error message', () => {
    renderList('Cannot reach the server.')

    expect(screen.getByRole('alert')).toHaveTextContent('Cannot reach the server.')
  })
})

const prefs = (changes: Partial<Conversation['preferences']>) => ({ mutedUntil: null, archived: false, pinned: false, ...changes })

function rowNames(listName = 'Conversations') {
  return within(screen.getByRole('list', { name: listName }))
    .getAllByRole('link')
    .map((link) => link.textContent ?? '')
}

describe('ConversationList with groups and chat settings', () => {
  it('shows a group by its name, with the sender in front of other people\'s messages', () => {
    const group = makeGroup()
    useChatStore.setState({
      conversationsLoaded: true,
      conversations: { g1: group },
      summaries: { g1: { unreadCount: 0, lastMessage: makeMessage({ conversationId: 'g1', senderId: OTHER, content: 'Tickets booked' }) } },
    })
    renderList()

    const row = screen.getByRole('link', { name: /Trip to Goa/ })
    expect(row).toHaveTextContent('Ravi Kumar: Tickets booked')
  })

  it('shows SYSTEM messages as a sentence, without ticks', () => {
    const group = makeGroup()
    useChatStore.setState({
      conversationsLoaded: true,
      conversations: { g1: group },
      summaries: {
        g1: {
          unreadCount: 0,
          lastMessage: makeSystemMessage({ event: 'GROUP_CREATED', actor: { id: ME, name: 'Asha Rao' }, name: 'Trip to Goa' }, { conversationId: 'g1' }),
        },
      },
    })
    renderList()

    expect(screen.getByRole('link', { name: /Trip to Goa/ })).toHaveTextContent('You created group "Trip to Goa"')
    expect(screen.queryByLabelText('Sent')).not.toBeInTheDocument()
  })

  it('a group without a name or another person does not crash', () => {
    useChatStore.setState({
      conversationsLoaded: true,
      conversations: { x: makeConversation({ id: 'x', otherUser: null }) },
    })
    renderList()

    expect(screen.getByRole('link', { name: /Unknown user/ })).toBeInTheDocument()
  })

  it('muted chats show a muted icon and a grey unread badge', () => {
    const muted = makeConversation({ id: 'm', preferences: prefs({ mutedUntil: '9999-12-31T23:59:59Z' }) })
    useChatStore.setState({
      conversationsLoaded: true,
      conversations: { m: muted },
      summaries: { m: { unreadCount: 2, lastMessage: makeMessage({ conversationId: 'm' }) } },
    })
    renderList()

    expect(screen.getByLabelText('Muted')).toBeInTheDocument()
    expect(screen.getByLabelText('2 unread messages')).toHaveClass('bg-gray-400')
  })

  it('pinned chats come first; archived chats are in the "Archived" folder', async () => {
    useChatStore.setState({
      conversationsLoaded: true,
      conversations: {
        new: makeConversation({ id: 'new', createdAt: '2026-11-04T10:00:00.000000Z', otherUser: profile('u1', 'Newest Person') }),
        pinned: makeConversation({ id: 'pinned', createdAt: '2026-10-01T10:00:00.000000Z', otherUser: profile('u2', 'Pinned Person'), preferences: prefs({ pinned: true }) }),
        old: makeConversation({ id: 'old', createdAt: '2026-11-04T09:00:00.000000Z', otherUser: profile('u3', 'Archived Person'), preferences: prefs({ archived: true }) }),
      },
      summaries: { old: { unreadCount: 1, lastMessage: null } },
    })
    renderList()
    const user = userEvent.setup()

    const names = rowNames()
    expect(names[0]).toContain('Pinned Person')
    expect(names[1]).toContain('Newest Person')
    expect(names.join()).not.toContain('Archived Person')
    expect(screen.getByLabelText('Pinned')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /Archived \(1\)/ }))
    expect(screen.getByRole('heading', { name: 'Archived' })).toBeInTheDocument()
    expect(rowNames('Archived conversations')).toHaveLength(1)
    expect(rowNames('Archived conversations')[0]).toContain('Archived Person')

    await user.click(screen.getByRole('button', { name: 'Back to chats' }))
    expect(screen.getByRole('heading', { name: 'Chats' })).toBeInTheDocument()
  })

  it('the chat menu pins, mutes and archives, and stores the answer', async () => {
    const chat = makeConversation()
    useChatStore.setState({ conversationsLoaded: true, conversations: { c1: chat } })
    api.pinConversation.mockResolvedValue({ ...chat, preferences: prefs({ pinned: true }) })
    api.muteConversation.mockResolvedValue({ ...chat, preferences: prefs({ mutedUntil: '9999-12-31T23:59:59Z' }) })
    api.archiveConversation.mockResolvedValue({ ...chat, preferences: prefs({ archived: true }) })
    renderList()
    const user = userEvent.setup()
    const openMenu = () => user.click(screen.getByRole('button', { name: 'Chat options for Ravi Kumar' }))

    await openMenu()
    await user.click(screen.getByRole('menuitem', { name: 'Pin chat' }))
    expect(api.pinConversation).toHaveBeenCalledWith('c1')
    expect(await screen.findByLabelText('Pinned')).toBeInTheDocument()

    await openMenu()
    await user.click(screen.getByRole('menuitem', { name: 'Mute for 8 hours' }))
    expect(api.muteConversation).toHaveBeenCalledWith('c1', 'EIGHT_HOURS')

    await openMenu()
    await user.click(screen.getByRole('menuitem', { name: 'Archive chat' }))
    expect(api.archiveConversation).toHaveBeenCalledWith('c1')
    expect(await screen.findByRole('button', { name: /Archived \(1\)/ })).toBeInTheDocument()
  })

  it('a muted chat offers "Unmute"; the pin limit is explained', async () => {
    const chat = makeConversation({ preferences: prefs({ mutedUntil: '9999-12-31T23:59:59Z' }) })
    useChatStore.setState({ conversationsLoaded: true, conversations: { c1: chat } })
    api.pinConversation.mockRejectedValue(new AppError('UNKNOWN', 'conflict', 409, {}, 'PIN_LIMIT_REACHED'))
    renderList()
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: 'Chat options for Ravi Kumar' }))
    expect(screen.getByRole('menuitem', { name: 'Unmute' })).toBeInTheDocument()
    expect(screen.queryByRole('menuitem', { name: 'Mute for 8 hours' })).not.toBeInTheDocument()

    await user.click(screen.getByRole('menuitem', { name: 'Pin chat' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('You can only pin up to 3 chats.')
  })

  it('"New group" opens the new group dialog', async () => {
    useChatStore.setState({ conversationsLoaded: true })
    renderList()

    await userEvent.setup().click(screen.getByRole('button', { name: 'New group' }))

    expect(screen.getByRole('dialog', { name: 'New group: add members' })).toBeInTheDocument()
  })
})
