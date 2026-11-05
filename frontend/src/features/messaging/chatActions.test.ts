import * as chatApi from '../../services/api/chatApi'
import { AppError } from '../../services/api/appError'
import { useAuthStore } from '../../store/authStore'
import { useChatStore } from '../../store/chatStore'
import { ME, OTHER, makeGroup, makeMessage } from '../../test/chatFixtures'
import { fakeTokens } from '../../test/renderWithProviders'
import {
  addGroupMembers,
  changeMemberRole,
  createGroup,
  describeGroupError,
  loadLatestMessages,
  loadOlderMessages,
  refusedUserIds,
  retryMessage,
  sendMessage,
  setMuted,
  setPinned,
} from './chatActions'
import { handleRealtimeEvent } from './realtime'

vi.mock('../../services/api/chatApi')
const api = vi.mocked(chatApi)

const messagesOf = () => useChatStore.getState().messages['c1'] ?? []

beforeEach(() => {
  vi.resetAllMocks()
  useChatStore.getState().reset()
  useAuthStore.setState({ ...useAuthStore.getState(), user: { ...fakeTokens().user, id: ME }, accessToken: 'a' })
})

describe('optimistic sending', () => {
  it('shows the message at once as SENDING, then replaces it with the server copy', async () => {
    let resolveSend!: (message: ReturnType<typeof makeMessage>) => void
    api.sendMessage.mockReturnValue(new Promise((resolve) => (resolveSend = resolve)))

    const sending = sendMessage('c1', '  hello  ')

    // before the server answered
    expect(messagesOf()).toHaveLength(1)
    expect(messagesOf()[0]).toMatchObject({ content: 'hello', localStatus: 'SENDING', senderId: ME })
    expect(useChatStore.getState().summaries['c1'].lastMessage?.content).toBe('hello')

    resolveSend(makeMessage({ id: 'srv-1', senderId: ME, content: 'hello' }))
    await sending

    expect(messagesOf().map((m) => m.id)).toEqual(['srv-1'])
    expect(messagesOf()[0].localStatus).toBeUndefined()
    expect(messagesOf()[0].status).toBe('SENT')
    expect(useChatStore.getState().summaries['c1'].lastMessage?.id).toBe('srv-1')
  })

  it('sends the idempotency key and type with the request', async () => {
    api.sendMessage.mockResolvedValue(makeMessage({ senderId: ME }))

    await sendMessage('c1', 'hi')

    const body = api.sendMessage.mock.calls[0][0]
    expect(body.conversationId).toBe('c1')
    expect(body.content).toBe('hi')
    expect(body.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/)
  })

  it('does not send blank text', async () => {
    await sendMessage('c1', '   \n ')

    expect(api.sendMessage).not.toHaveBeenCalled()
    expect(messagesOf()).toHaveLength(0)
  })

  it('marks the message FAILED when sending fails, and Retry reuses the SAME key (no duplicate)', async () => {
    api.sendMessage.mockRejectedValueOnce(new AppError('NETWORK', 'Cannot reach the server.'))

    await sendMessage('c1', 'important')

    expect(messagesOf()[0].localStatus).toBe('FAILED')
    expect(messagesOf()[0].errorMessage).toMatch(/Retry/)
    const firstKey = api.sendMessage.mock.calls[0][0].idempotencyKey

    api.sendMessage.mockResolvedValueOnce(makeMessage({ id: 'srv-9', senderId: ME, content: 'important' }))
    await retryMessage('c1', firstKey)

    expect(api.sendMessage).toHaveBeenCalledTimes(2)
    expect(api.sendMessage.mock.calls[1][0].idempotencyKey).toBe(firstKey) // same key -> server cannot create a second message
    expect(messagesOf().map((m) => m.id)).toEqual(['srv-9'])
  })

  it('shows SENDING again while a retry is running', async () => {
    api.sendMessage.mockRejectedValueOnce(new AppError('SERVER', 'boom', 500))
    await sendMessage('c1', 'x')
    const key = api.sendMessage.mock.calls[0][0].idempotencyKey

    let finish!: () => void
    api.sendMessage.mockReturnValueOnce(new Promise((resolve) => (finish = () => resolve(makeMessage({ senderId: ME, content: 'x' })))))
    const retrying = retryMessage('c1', key)

    expect(messagesOf()[0].localStatus).toBe('SENDING')
    expect(messagesOf()[0].errorMessage).toBeUndefined()
    finish()
    await retrying
  })

  it('explains "blocked" clearly', async () => {
    api.sendMessage.mockRejectedValue(
      new AppError('FORBIDDEN', 'forbidden', 403, {}, 'MESSAGING_NOT_ALLOWED'),
    )

    await sendMessage('c1', 'hi')

    expect(messagesOf()[0].errorMessage).toBe("You can't message this user.")
  })

  it('retry does nothing for a message that is not failed', async () => {
    api.sendMessage.mockResolvedValue(makeMessage({ senderId: ME }))
    await sendMessage('c1', 'ok')

    await retryMessage('c1', 'unknown-key')

    expect(api.sendMessage).toHaveBeenCalledTimes(1)
  })

  it('shows ONE message when the live event arrives before the POST answer', async () => {
    let resolveSend!: (message: ReturnType<typeof makeMessage>) => void
    api.sendMessage.mockReturnValue(new Promise((resolve) => (resolveSend = resolve)))
    const sending = sendMessage('c1', 'hello')

    const saved = makeMessage({ id: 'srv-1', senderId: ME, content: 'hello' })
    handleRealtimeEvent({ type: 'MESSAGE_CREATED', eventId: 'e1', conversationId: 'c1', data: saved, timestamp: '' })
    expect(messagesOf()).toHaveLength(1) // pending copy already replaced

    resolveSend(saved)
    await sending
    expect(messagesOf().map((m) => m.id)).toEqual(['srv-1'])
  })
})

describe('loading history', () => {
  it('loads the newest page, then older pages, without duplicates', async () => {
    const newest = [makeMessage({ id: 'm3', createdAt: '2026-11-04T10:00:03.000000Z' }), makeMessage({ id: 'm2', createdAt: '2026-11-04T10:00:02.000000Z' })]
    const older = [makeMessage({ id: 'm2', createdAt: '2026-11-04T10:00:02.000000Z' }), makeMessage({ id: 'm1', createdAt: '2026-11-04T10:00:01.000000Z' })]
    api.getMessages.mockResolvedValueOnce({ items: newest, nextCursor: 'cursor-a', hasMore: true })
    api.getMessages.mockResolvedValueOnce({ items: older, nextCursor: null, hasMore: false })

    await loadLatestMessages('c1')
    expect(messagesOf().map((m) => m.id)).toEqual(['m2', 'm3'])
    expect(useChatStore.getState().paging['c1']).toMatchObject({ loaded: true, nextCursor: 'cursor-a', hasMore: true })
    expect(api.getMessages).toHaveBeenCalledWith('c1', { limit: 30 })

    await loadOlderMessages('c1')
    expect(api.getMessages).toHaveBeenLastCalledWith('c1', { before: 'cursor-a', limit: 30 })
    expect(messagesOf().map((m) => m.id)).toEqual(['m1', 'm2', 'm3']) // sorted, m2 only once
    expect(useChatStore.getState().paging['c1']).toMatchObject({ hasMore: false, loadingOlder: false })
  })

  it('does not ask for older messages when there are none, or while one request is running', async () => {
    api.getMessages.mockResolvedValueOnce({ items: [makeMessage({ id: 'm1' })], nextCursor: 'c', hasMore: true })
    await loadLatestMessages('c1')
    let finish!: () => void
    api.getMessages.mockReturnValueOnce(new Promise((resolve) => (finish = () => resolve({ items: [], nextCursor: null, hasMore: false }))))

    const first = loadOlderMessages('c1')
    await loadOlderMessages('c1') // second call while the first runs
    finish()
    await first
    await loadOlderMessages('c1') // hasMore is false now

    expect(api.getMessages).toHaveBeenCalledTimes(2)
  })

  it('opening again keeps the older-page position', async () => {
    api.getMessages.mockResolvedValue({ items: [makeMessage({ id: 'm1' })], nextCursor: 'first', hasMore: true })
    await loadLatestMessages('c1')
    api.getMessages.mockResolvedValue({ items: [makeMessage({ id: 'm2' })], nextCursor: 'other', hasMore: true })

    await loadLatestMessages('c1')

    expect(useChatStore.getState().paging['c1'].nextCursor).toBe('first')
    expect(messagesOf()).toHaveLength(2)
  })
})

describe('groups', () => {
  it('createGroup trims the input, leaves out an empty description and stores the new group', async () => {
    api.createGroup.mockResolvedValue(makeGroup({ id: 'g-new' }))

    const id = await createGroup('  Trip  ', '   ', ['u-a', 'u-b'])

    expect(id).toBe('g-new')
    expect(api.createGroup).toHaveBeenCalledWith({ name: 'Trip', memberIds: ['u-a', 'u-b'] })
    expect(useChatStore.getState().conversations['g-new']).toBeDefined()
  })

  it('every group action stores the conversation the server answers with', async () => {
    const after = makeGroup({ participants: [] })
    api.setMemberRole.mockResolvedValue(after)

    await changeMemberRole('g1', OTHER, 'ADMIN')

    expect(api.setMemberRole).toHaveBeenCalledWith('g1', OTHER, 'ADMIN')
    expect(useChatStore.getState().conversations['g1']).toEqual(after)
  })

  it('"not admin" / "not a member" answers reload the group (my view was out of date)', async () => {
    api.addMembers.mockRejectedValue(new AppError('FORBIDDEN', 'no', 403, {}, 'NOT_GROUP_ADMIN'))
    api.getConversation.mockResolvedValue(makeGroup({}, { role: 'MEMBER' }))

    await expect(addGroupMembers('g1', ['u-x'])).rejects.toMatchObject({ errorCode: 'NOT_GROUP_ADMIN' })
    await vi.waitFor(() => expect(useChatStore.getState().conversations['g1']?.membership.role).toBe('MEMBER'))
  })

  it('refusedUserIds reads the people a GROUP_ADD_NOT_ALLOWED answer names', () => {
    const refused = new AppError('FORBIDDEN', 'no', 403, {}, 'GROUP_ADD_NOT_ALLOWED', {
      errorCode: 'GROUP_ADD_NOT_ALLOWED',
      userIds: ['u-1', 7],
    })
    expect(refusedUserIds(refused)).toEqual(['u-1'])
    expect(refusedUserIds(new AppError('NETWORK', 'x'))).toEqual([])
  })

  it('describeGroupError has a friendly sentence for every group error code', () => {
    const codes = ['GROUP_ADD_NOT_ALLOWED', 'GROUP_FULL', 'NOT_GROUP_ADMIN', 'NOT_A_MEMBER', 'ALREADY_MEMBER', 'CANNOT_CHANGE_SELF', 'PIN_LIMIT_REACHED']
    for (const code of codes) {
      expect(describeGroupError(new AppError('UNKNOWN', 'server text', 409, {}, code))).not.toBe('server text')
    }
    expect(describeGroupError(new AppError('UNKNOWN', 'server text', 409, {}, 'PIN_LIMIT_REACHED'))).toBe(
      'You can only pin up to 3 chats.',
    )
    expect(describeGroupError(new AppError('NETWORK', 'Cannot reach the server.'))).toBe('Cannot reach the server.')
  })
})

describe('my chat settings', () => {
  it('mute / unmute and pin / unpin call the right endpoint', async () => {
    api.muteConversation.mockResolvedValue(makeGroup())
    api.unmuteConversation.mockResolvedValue(makeGroup())
    api.pinConversation.mockResolvedValue(makeGroup())
    api.unpinConversation.mockResolvedValue(makeGroup())

    await setMuted('g1', 'ONE_WEEK')
    await setMuted('g1', null)
    await setPinned('g1', true)
    await setPinned('g1', false)

    expect(api.muteConversation).toHaveBeenCalledWith('g1', 'ONE_WEEK')
    expect(api.unmuteConversation).toHaveBeenCalledWith('g1')
    expect(api.pinConversation).toHaveBeenCalledWith('g1')
    expect(api.unpinConversation).toHaveBeenCalledWith('g1')
  })
})

describe('sending in a group', () => {
  it('"only admins can send" is explained and the group is reloaded (to show the banner)', async () => {
    api.sendMessage.mockRejectedValue(new AppError('FORBIDDEN', 'no', 403, {}, 'ONLY_ADMINS_CAN_SEND'))
    api.getConversation.mockResolvedValue(makeGroup({ id: 'c1' }, { role: 'MEMBER', onlyAdminsCanSend: true }))

    await sendMessage('c1', 'hi all')

    expect(messagesOf()[0].errorMessage).toBe('Only admins can send messages to this group.')
    await vi.waitFor(() => expect(api.getConversation).toHaveBeenCalledWith('c1'))
  })

  it('a 404 (I am no longer in the group) reads sensibly and reloads the conversation', async () => {
    api.sendMessage.mockRejectedValue(new AppError('NOT_FOUND', 'Conversation not found', 404))
    api.getConversation.mockResolvedValue(makeGroup({ id: 'c1' }, { active: false }))

    await sendMessage('c1', 'hello?')

    expect(messagesOf()[0].errorMessage).toBe("You can't send messages to this chat any more.")
    await vi.waitFor(() => expect(useChatStore.getState().conversations['c1']?.membership.active).toBe(false))
  })
})
