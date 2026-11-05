import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { cancelAttachment, isUploadPending, retryAttachment, sendAttachment } from '../../features/media/attachmentActions'
import { ensureConversation, loadLatestMessages, retryMessage, sendMessage } from '../../features/messaging/chatActions'
import { scheduleRead } from '../../features/messaging/realtime'
import { refreshUnreadCountAfterOpeningChat } from '../../features/notifications/notificationActions'
import { webSocketManager } from '../../services/websocket/websocketManager'
import * as chatApi from '../../services/api/chatApi'
import { getPresence } from '../../services/api/presenceApi'
import { useAuthStore } from '../../store/authStore'
import { useChatStore } from '../../store/chatStore'
import { ME, OTHER, THIRD, makeConversation, makeGroup, makeMediaMessage, makeMessage, makeSystemMessage } from '../../test/chatFixtures'
import { fakeTokens, renderWithProviders } from '../../test/renderWithProviders'
import { callEngine } from '../../features/calls/callEngine'
import { useCallStore } from '../../features/calls/callStore'
import ChatWindow from './ChatWindow'

vi.mock('../../features/messaging/chatActions')
vi.mock('../../features/media/attachmentActions')
vi.mock('../../services/api/chatApi')
vi.mock('../../services/api/presenceApi')
vi.mock('../../features/notifications/notificationActions')
vi.mock('../../features/messaging/realtime', () => ({
  handleRealtimeEvent: vi.fn(),
  scheduleRead: vi.fn(),
}))
vi.mock('../../features/calls/callEngine', () => ({ callEngine: { startCall: vi.fn() } }))
vi.mock('../../services/websocket/websocketManager', () => ({
  webSocketManager: { subscribe: vi.fn(() => vi.fn()), onConnected: vi.fn(() => vi.fn()), publish: vi.fn().mockReturnValue(true) },
}))

function renderWindow() {
  return renderWithProviders(
    <Routes>
      <Route path="/chats/:conversationId" element={<ChatWindow conversationId="c1" />} />
      <Route path="/chats" element={<p>chat list page</p>} />
    </Routes>,
    { route: '/chats/c1' },
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(ensureConversation).mockResolvedValue()
  vi.mocked(loadLatestMessages).mockResolvedValue()
  vi.mocked(getPresence).mockResolvedValue({ userId: OTHER, visible: true, online: false, lastSeenAt: null })
  useChatStore.getState().reset()
  useChatStore.setState({
    conversationsLoaded: true,
    conversations: { c1: makeConversation() },
    summaries: { c1: { unreadCount: 2, lastMessage: null } },
    paging: { c1: { loaded: true, hasMore: false, nextCursor: null, loadingOlder: false } },
  })
  useAuthStore.setState({ ...useAuthStore.getState(), user: { ...fakeTokens().user, id: ME } })
})

describe('ChatWindow', () => {
  it('shows the other person in the header and loads the conversation', async () => {
    renderWindow()

    expect(await screen.findByRole('heading', { name: 'Ravi Kumar' })).toBeInTheDocument()
    expect(ensureConversation).toHaveBeenCalledWith('c1')
    expect(loadLatestMessages).toHaveBeenCalledWith('c1')
    expect(useChatStore.getState().openConversationId).toBe('c1')
  })

  it('has voice and video call buttons in a private chat; they start the call with the other person', async () => {
    renderWindow()

    await userEvent.click(await screen.findByRole('button', { name: 'Video call' }))
    expect(callEngine.startCall).toHaveBeenCalledWith(expect.objectContaining({ id: OTHER }), 'VIDEO')
    await userEvent.click(screen.getByRole('button', { name: 'Voice call' }))
    expect(callEngine.startCall).toHaveBeenCalledWith(expect.objectContaining({ id: OTHER }), 'AUDIO')
  })

  it('disables the call buttons while a call is running', async () => {
    useCallStore.getState().patch({ state: 'active' })
    renderWindow()
    expect(await screen.findByRole('button', { name: 'Video call' })).toBeDisabled()
    useCallStore.getState().reset()
  })

  it('has no call buttons in a group (group calls are not supported)', async () => {
    useChatStore.setState({ conversations: { c1: makeGroup({ id: 'c1' }) } })
    renderWindow()
    expect(await screen.findByRole('button', { name: 'Group info' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Video call' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Voice call' })).not.toBeInTheDocument()
  })

  it('shows "online" with a green dot in the header of a private chat', async () => {
    vi.mocked(getPresence).mockResolvedValue({ userId: OTHER, visible: true, online: true, lastSeenAt: new Date().toISOString() })
    renderWindow()

    expect(await screen.findByTestId('presence')).toHaveTextContent('online')
    expect(getPresence).toHaveBeenCalledWith(OTHER)
  })

  it('shows "last seen ..." when the other person is offline', async () => {
    vi.mocked(getPresence).mockResolvedValue({ userId: OTHER, visible: true, online: false, lastSeenAt: new Date().toISOString() })
    renderWindow()

    expect(await screen.findByTestId('presence')).toHaveTextContent(/^last seen today at \d\d:\d\d$/)
  })

  it('shows nothing when privacy hides the status, or when the person was never seen', async () => {
    vi.mocked(getPresence).mockResolvedValue({ userId: OTHER, visible: false, online: null, lastSeenAt: null })
    renderWindow()
    await waitFor(() => expect(getPresence).toHaveBeenCalled())
    expect(screen.queryByTestId('presence')).not.toBeInTheDocument()
  })

  it('typing replaces the presence line while the other person types', async () => {
    vi.mocked(getPresence).mockResolvedValue({ userId: OTHER, visible: true, online: true, lastSeenAt: null })
    useChatStore.setState({ typing: { c1: [OTHER] } })
    renderWindow()

    expect(await screen.findByText('typing...')).toBeInTheDocument()
    expect(screen.queryByTestId('presence')).not.toBeInTheDocument()
  })

  it('asks the server for the unread notification count again a few seconds after opening the chat', () => {
    renderWindow()
    expect(refreshUnreadCountAfterOpeningChat).toHaveBeenCalledTimes(1)
  })

  it('marks the conversation as open while it is shown and closed when it goes away', () => {
    const { unmount } = renderWindow()
    expect(useChatStore.getState().openConversationId).toBe('c1')

    unmount()
    expect(useChatStore.getState().openConversationId).toBeNull()
  })

  it('shows the messages with date separator and ticks', () => {
    useChatStore.setState({
      messages: {
        c1: [
          makeMessage({ id: 'in1', content: 'Hello Asha', createdAt: new Date().toISOString() }),
          makeMessage({ id: 'out1', senderId: ME, content: 'Hi Ravi', status: 'READ', createdAt: new Date().toISOString() }),
        ],
      },
    })
    renderWindow()

    expect(screen.getByText('Hello Asha')).toBeInTheDocument()
    expect(screen.getByText('Hi Ravi')).toBeInTheDocument()
    expect(screen.getByText('Today')).toBeInTheDocument()
    expect(screen.getByLabelText('Read')).toBeInTheDocument()
  })

  it('shows "typing..." only while the other person types', () => {
    renderWindow()
    expect(screen.queryByText('typing...')).not.toBeInTheDocument()

    useChatStore.getState().setTyping('c1', OTHER, true)
    return waitFor(() => expect(screen.getByText('typing...')).toBeInTheDocument())
  })

  it('draws the unread divider once, above the first unread message, and asks to mark the chat as read', async () => {
    useChatStore.setState({
      messages: {
        c1: [
          makeMessage({ id: 'old', content: 'seen before', myReceipt: 'READ' }),
          makeMessage({ id: 'new1', content: 'unread A' }),
          makeMessage({ id: 'new2', content: 'unread B' }),
        ],
      },
    })
    renderWindow()

    expect(await screen.findByText('2 unread messages')).toBeInTheDocument()
    const texts = screen.getAllByText(/seen before|unread A|unread B|2 unread messages/).map((node) => node.textContent)
    expect(texts).toEqual(['seen before', '2 unread messages', 'unread A', 'unread B'])
    expect(scheduleRead).toHaveBeenCalledWith('c1')
  })

  it('has no divider when nothing was unread', async () => {
    useChatStore.setState({
      summaries: { c1: { unreadCount: 0, lastMessage: null } },
      messages: { c1: [makeMessage({ id: 'a', content: 'hello' })] },
    })
    renderWindow()

    await waitFor(() => expect(scheduleRead).toHaveBeenCalled())
    expect(screen.queryByText(/unread message/)).not.toBeInTheDocument()
  })

  it('does not subscribe to anything itself (typing arrives on the personal /user/queue/typing)', () => {
    renderWindow()

    expect(webSocketManager.subscribe).not.toHaveBeenCalled()
  })

  it('sends what the user types', async () => {
    renderWindow()
    const user = userEvent.setup()

    await user.type(screen.getByLabelText('Message'), 'good morning{Enter}')

    expect(sendMessage).toHaveBeenCalledWith('c1', 'good morning')
  })

  it('sends a dropped file as an attachment through the composer', async () => {
    renderWindow()
    await screen.findByRole('heading', { name: 'Ravi Kumar' })
    const dropped = new File(['pixels'], 'dropped.png', { type: 'image/png' })

    fireEvent.drop(screen.getByRole('heading', { name: 'Ravi Kumar' }), { dataTransfer: { files: [dropped], types: ['Files'] } })
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Send message' }))

    expect(sendAttachment).toHaveBeenCalledTimes(1)
    expect(vi.mocked(sendAttachment).mock.calls[0][0]).toBe('c1')
    expect(vi.mocked(sendAttachment).mock.calls[0][1]).toMatchObject({ fileName: 'dropped.png', kind: 'IMAGE' })
  })

  it('Retry of a media message goes through its upload, Retry of a text message through sendMessage', async () => {
    const user = userEvent.setup()
    const media = { ...makeMediaMessage({}, { id: 'local-k1', senderId: ME }), localStatus: 'FAILED' as const, idempotencyKey: 'k1', errorMessage: 'No connection.' }
    const text = { ...makeMessage({ id: 'local-k2', senderId: ME, content: 'plain' }), localStatus: 'FAILED' as const, idempotencyKey: 'k2', errorMessage: 'No connection.' }
    vi.mocked(isUploadPending).mockImplementation((key) => key === 'k1')
    useChatStore.setState({ messages: { c1: [media, text] } })
    renderWindow()

    const retryButtons = await screen.findAllByRole('button', { name: 'Retry' })
    await user.click(retryButtons[0])
    await user.click(retryButtons[1])

    expect(retryAttachment).toHaveBeenCalledWith('k1')
    expect(retryMessage).toHaveBeenCalledWith('c1', 'k2')
  })

  it('Cancel on an uploading media message cancels its upload', async () => {
    const uploading = {
      ...makeMediaMessage({ mediaId: '' }, { id: 'local-k3', senderId: ME }),
      localStatus: 'SENDING' as const, idempotencyKey: 'k3', uploadProgress: 30, previewUrl: 'blob:x',
    }
    useChatStore.setState({ messages: { c1: [uploading] } })
    renderWindow()

    await userEvent.setup().click(await screen.findByRole('button', { name: 'Cancel upload' }))

    expect(cancelAttachment).toHaveBeenCalledWith('k3')
  })

  it('shows an error with a way back when the conversation cannot be opened', async () => {
    vi.mocked(ensureConversation).mockRejectedValue(new Error('x'))
    renderWindow()

    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Back to chats' })).toHaveAttribute('href', '/chats')
  })
})

describe('ChatWindow for a group', () => {
  function openGroup(group = makeGroup(), messages = [] as ReturnType<typeof makeMessage>[], unreadCount = 0) {
    useChatStore.setState({
      conversations: { c1: { ...group, id: 'c1' } },
      summaries: { c1: { unreadCount, lastMessage: null } },
      messages: { c1: messages.map((m) => ({ ...m, conversationId: 'c1' })) },
    })
    return renderWindow()
  }

  it('does not ask for presence in a group (the header shows the members)', async () => {
    openGroup()
    await screen.findByRole('heading', { name: 'Trip to Goa' })
    expect(getPresence).not.toHaveBeenCalled()
    expect(screen.queryByTestId('presence')).not.toBeInTheDocument()
  })

  it('shows the group name and the members in the header', () => {
    openGroup()

    expect(screen.getByRole('heading', { name: 'Trip to Goa' })).toBeInTheDocument()
    expect(screen.getByText('You, Meena Iyer, Ravi Kumar')).toBeInTheDocument()
  })

  it('shows who is typing instead of the members', async () => {
    openGroup()

    useChatStore.getState().setTyping('c1', OTHER, true)
    expect(await screen.findByText('Ravi Kumar is typing...')).toBeInTheDocument()
    useChatStore.getState().setTyping('c1', THIRD, true)
    expect(await screen.findByText('Ravi Kumar and Meena Iyer are typing...')).toBeInTheDocument()
  })

  it('shows sender names, SYSTEM messages as notes, and ignores SYSTEM messages for the unread divider', async () => {
    openGroup(makeGroup(), [
      makeSystemMessage({ event: 'GROUP_CREATED', actor: { id: OTHER, name: 'Ravi Kumar' }, name: 'Trip to Goa' }),
      makeMessage({ id: 'r1', senderId: OTHER, content: 'Welcome all' }),
      makeSystemMessage({ event: 'MEMBERS_ADDED', actor: { id: OTHER, name: 'Ravi Kumar' }, users: [{ id: THIRD, name: 'Meena Iyer' }] }),
    ], 1)

    expect(screen.getByText('Ravi Kumar created group "Trip to Goa"')).toBeInTheDocument()
    expect(screen.getByText('Ravi Kumar added Meena Iyer')).toBeInTheDocument()
    expect(screen.getByTestId('message')).toHaveTextContent('Ravi Kumar')
    expect(screen.getByTestId('message')).toHaveTextContent('Welcome all')
    // 1 unread: the divider goes above the only real message, not above the last SYSTEM note
    const order = screen.getAllByText(/1 unread message|Welcome all|added Meena/).map((node) => node.textContent)
    expect(order).toEqual(['1 unread message', 'Welcome all', 'Ravi Kumar added Meena Iyer'])
  })

  it('replaces the composer when I am no longer a participant', () => {
    openGroup(makeGroup({}, { active: false }))

    expect(screen.getByText("You can't send messages to this group because you're no longer a participant.")).toBeInTheDocument()
    expect(screen.queryByLabelText('Message')).not.toBeInTheDocument()
  })

  it('replaces the composer when only admins may send and I am not one', () => {
    openGroup(makeGroup({}, { role: 'MEMBER', onlyAdminsCanSend: true }))

    expect(screen.getByText('Only admins can send messages.')).toBeInTheDocument()
    expect(screen.queryByLabelText('Message')).not.toBeInTheDocument()
  })

  it('an admin can still write when only admins may send', () => {
    openGroup(makeGroup({}, { role: 'ADMIN', onlyAdminsCanSend: true }))

    expect(screen.getByLabelText('Message')).toBeInTheDocument()
  })

  it('opens and closes the group info panel', async () => {
    openGroup()
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: 'Group info' }))
    expect(screen.getByRole('complementary', { name: 'Group info' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: 'Close group info' }))
    expect(screen.queryByRole('complementary', { name: 'Group info' })).not.toBeInTheDocument()
  })

  it('"Message info" on my message shows who read it and who did not yet', async () => {
    vi.mocked(chatApi.getMessageReceipts).mockResolvedValue([
      { userId: OTHER, status: 'READ', deliveredAt: '2026-11-04T10:00:01Z', readAt: '2026-11-04T10:00:05Z' },
      { userId: THIRD, status: 'SENT', deliveredAt: null, readAt: null },
    ])
    openGroup(makeGroup(), [makeMessage({ id: 'mine-1', senderId: ME, content: 'Who is coming?', status: 'SENT' })])
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: 'Message info' }))

    const dialog = screen.getByRole('dialog', { name: 'Message info' })
    expect(chatApi.getMessageReceipts).toHaveBeenCalledWith('mine-1')
    expect(await within(dialog).findByRole('region', { name: 'Read by' })).toHaveTextContent('Ravi Kumar')
    expect(within(dialog).getByRole('region', { name: 'Waiting' })).toHaveTextContent('Meena Iyer')
    expect(within(dialog).getByRole('region', { name: 'Delivered to' })).toHaveTextContent('Nobody')
  })

  it('private chats have no group info button but do have the chat menu', () => {
    renderWindow()

    expect(screen.queryByRole('button', { name: 'Group info' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Chat options for Ravi Kumar' })).toBeInTheDocument()
  })
})
