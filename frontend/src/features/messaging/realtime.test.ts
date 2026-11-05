import * as chatApi from '../../services/api/chatApi'
import { useAuthStore } from '../../store/authStore'
import { useChatStore } from '../../store/chatStore'
import { ME, OTHER, THIRD, makeConversation, makeGroup, makeMessage, makeSystemMessage } from '../../test/chatFixtures'
import { fakeTokens } from '../../test/renderWithProviders'
import { getSyncCursor } from './cursorStorage'
import { startRealtime, syncAfterConnect, type RealtimeConnection } from './realtime'

vi.mock('../../services/api/chatApi')
const api = vi.mocked(chatApi)

// A fake connection: tests call the handlers the way the WebSocket would.
function fakeConnection() {
  const handlers = new Map<string, (event: unknown) => void>()
  let connectedListener: () => void = () => undefined
  const connection: RealtimeConnection = {
    subscribe: (destination, handler) => {
      handlers.set(destination, handler)
      return () => handlers.delete(destination)
    },
    onConnected: (listener) => {
      connectedListener = listener
      return () => undefined
    },
  }
  return {
    connection,
    handlers,
    send: (destination: string, event: unknown) => handlers.get(destination)?.(event),
    fireConnected: () => connectedListener(),
  }
}

function created(message: ReturnType<typeof makeMessage>) {
  return { type: 'MESSAGE_CREATED', eventId: 'e', conversationId: message.conversationId, data: message, timestamp: '' }
}

function setVisibility(state: 'visible' | 'hidden') {
  Object.defineProperty(document, 'visibilityState', { value: state, configurable: true })
}

const store = () => useChatStore.getState()
const flushTimers = () => vi.advanceTimersByTimeAsync(250)

let stop: () => void
let live: ReturnType<typeof fakeConnection>

beforeEach(() => {
  vi.useFakeTimers()
  vi.resetAllMocks()
  api.markDelivered.mockResolvedValue()
  api.markRead.mockResolvedValue()
  api.getConversations.mockResolvedValue([makeConversation()])
  api.getSummaries.mockResolvedValue([])
  setVisibility('visible')
  store().reset()
  store().upsertConversation(makeConversation())
  useAuthStore.setState({ ...useAuthStore.getState(), user: { ...fakeTokens().user, id: ME }, accessToken: 'a' })
  live = fakeConnection()
  stop = startRealtime(live.connection)
})

afterEach(() => {
  stop()
  vi.useRealTimers()
})

describe('subscriptions', () => {
  it('listens to the five personal queues (typing included, no per-conversation topic)', () => {
    expect([...live.handlers.keys()].sort()).toEqual([
      '/user/queue/acks',
      '/user/queue/errors',
      '/user/queue/messages',
      '/user/queue/receipts',
      '/user/queue/typing',
    ])
  })
})

describe('a message from the other person', () => {
  it('in a conversation I am not looking at: unread +1 and ONE batched delivered acknowledgement', async () => {
    const first = makeMessage({ id: 'a1' })
    const second = makeMessage({ id: 'a2' })

    live.send('/user/queue/messages', created(first))
    live.send('/user/queue/messages', created(second))
    await flushTimers()

    expect(store().messages['c1'].map((m) => m.id)).toEqual(['a1', 'a2'])
    expect(store().summaries['c1'].unreadCount).toBe(2)
    expect(api.markDelivered).toHaveBeenCalledTimes(1)
    expect(api.markDelivered).toHaveBeenCalledWith(['a1', 'a2'])
    expect(api.markRead).not.toHaveBeenCalled()
  })

  it('in the open conversation with a visible tab: delivered AND read, no unread', async () => {
    store().setOpenConversation('c1')
    const message = makeMessage({ id: 'b1' })

    live.send('/user/queue/messages', created(message))
    await flushTimers()

    expect(store().summaries['c1'].unreadCount).toBe(0)
    expect(api.markDelivered).toHaveBeenCalledWith(['b1'])
    expect(api.markRead).toHaveBeenCalledWith('c1', 'b1')
    expect(store().messages['c1'][0].myReceipt).toBe('READ')
  })

  it('in the open conversation but with a hidden tab: counted as unread until the tab is visible again', async () => {
    store().setOpenConversation('c1')
    setVisibility('hidden')

    live.send('/user/queue/messages', created(makeMessage({ id: 'h1' })))
    await flushTimers()

    expect(store().summaries['c1'].unreadCount).toBe(1)
    expect(api.markDelivered).toHaveBeenCalledWith(['h1']) // received, so delivered
    expect(api.markRead).not.toHaveBeenCalled()

    setVisibility('visible')
    document.dispatchEvent(new Event('visibilitychange'))
    await flushTimers()

    expect(api.markRead).toHaveBeenCalledWith('c1', 'h1')
    expect(store().summaries['c1'].unreadCount).toBe(0)
  })

  it('the same event twice counts once', async () => {
    const message = makeMessage({ id: 'd1' })

    live.send('/user/queue/messages', created(message))
    live.send('/user/queue/messages', created(message))
    await flushTimers()

    expect(store().messages['c1']).toHaveLength(1)
    expect(store().summaries['c1'].unreadCount).toBe(1)
    expect(api.markDelivered).toHaveBeenCalledWith(['d1'])
  })

  it('a conversation I do not know yet triggers a reload of the chat list', async () => {
    store().reset()
    api.getConversations.mockResolvedValue([makeConversation()])
    api.getSummaries.mockResolvedValue([{ conversationId: 'c1', unreadCount: 1, lastMessage: null }])

    live.send('/user/queue/messages', created(makeMessage({ id: 'n1' })))
    await flushTimers()

    expect(api.getConversations).toHaveBeenCalled()
    expect(Object.keys(store().conversations)).toEqual(['c1'])
  })
})

describe('my own messages arriving from the server', () => {
  it('(sent from another device) are added without unread or acknowledgements', async () => {
    live.send('/user/queue/messages', created(makeMessage({ id: 'mine', senderId: ME })))
    await flushTimers()

    expect(store().messages['c1']).toHaveLength(1)
    expect(store().summaries['c1'].unreadCount).toBe(0)
    expect(api.markDelivered).not.toHaveBeenCalled()
  })
})

describe('receipts for my messages', () => {
  it('moves SENT -> DELIVERED -> READ and ignores a late DELIVERED', () => {
    store().addServerMessages('c1', [makeMessage({ id: 'out', senderId: ME, status: 'SENT' })])
    const receipt = (status: 'DELIVERED' | 'READ') => ({
      type: 'RECEIPT_UPDATED',
      eventId: 'e',
      conversationId: 'c1',
      data: { messageId: 'out', conversationId: 'c1', userId: OTHER, status },
      timestamp: '',
    })

    live.send('/user/queue/receipts', receipt('DELIVERED'))
    expect(store().messages['c1'][0].status).toBe('DELIVERED')
    live.send('/user/queue/receipts', receipt('READ'))
    expect(store().messages['c1'][0].status).toBe('READ')
    live.send('/user/queue/receipts', receipt('DELIVERED')) // arrives late
    expect(store().messages['c1'][0].status).toBe('READ')
  })

  it('also updates the tick in the chat list preview', () => {
    const out = makeMessage({ id: 'out', senderId: ME, status: 'SENT' })
    store().addServerMessages('c1', [out])

    live.send('/user/queue/receipts', {
      type: 'RECEIPT_UPDATED',
      eventId: 'e',
      conversationId: 'c1',
      data: { messageId: 'out', conversationId: 'c1', userId: OTHER, status: 'READ' },
      timestamp: '',
    })

    expect(store().summaries['c1'].lastMessage?.status).toBe('READ')
  })
})

describe('typing and errors', () => {
  it('shows the other person typing and hides it again by itself', () => {
    const typing = (userId: string, on: boolean) => ({
      type: 'TYPING', eventId: 'e', conversationId: 'c1', data: { conversationId: 'c1', userId, typing: on }, timestamp: '',
    })

    live.send('/user/queue/typing', typing(OTHER, true))
    expect(store().typing['c1']).toEqual([OTHER])

    vi.advanceTimersByTime(5100) // no "stopped" event arrived
    expect(store().typing['c1']).toEqual([])

    live.send('/user/queue/typing', typing(OTHER, true))
    live.send('/user/queue/typing', typing(OTHER, false))
    expect(store().typing['c1']).toEqual([])
  })

  it('ignores my own typing event', () => {
    live.send('/user/queue/typing', {
      type: 'TYPING', eventId: 'e', conversationId: 'c1', data: { conversationId: 'c1', userId: ME, typing: true }, timestamp: '',
    })

    expect(store().typing['c1']).toBeUndefined()
  })

  it('an ERROR event for a sent message marks it FAILED', () => {
    store().addPending({
      id: 'local-k', conversationId: 'c1', senderId: ME, type: 'TEXT', content: 'x', createdAt: new Date().toISOString(),
      status: null, myReceipt: null, localStatus: 'SENDING', idempotencyKey: 'k',
    })

    live.send('/user/queue/errors', {
      type: 'ERROR', eventId: 'e', conversationId: 'c1', data: { code: 'MESSAGING_NOT_ALLOWED', message: 'no', idempotencyKey: 'k' }, timestamp: '',
    })

    expect(store().messages['c1'][0]).toMatchObject({ localStatus: 'FAILED', errorMessage: "You can't message this user." })
  })

  it('ignores events it does not understand', () => {
    expect(() => {
      live.send('/user/queue/messages', { nonsense: true })
      live.send('/user/queue/messages', null)
      live.send('/user/queue/messages', { type: 'SOMETHING_NEW' })
    }).not.toThrow()
  })
})

describe('sync after (re)connecting', () => {
  it('first ever sync: no cursor, the answer only gives the starting point which is remembered', async () => {
    api.syncMessages.mockResolvedValue({ items: [], nextCursor: 'head-1', hasMore: false })

    await syncAfterConnect()

    expect(api.syncMessages).toHaveBeenCalledWith(undefined, 100)
    expect(getSyncCursor(ME)).toBe('head-1')
  })

  it('later syncs start from the saved cursor, follow all pages and merge without duplicates', async () => {
    localStorage.setItem(`chat-app-sync-cursor:${ME}`, 'old-cursor')
    store().addServerMessages('c1', [makeMessage({ id: 'already' })])
    api.syncMessages
      .mockResolvedValueOnce({
        items: [makeMessage({ id: 'already' }), makeMessage({ id: 's1' })],
        nextCursor: 'page-1',
        hasMore: true,
      })
      .mockResolvedValueOnce({ items: [makeMessage({ id: 's2' })], nextCursor: 'page-2', hasMore: false })

    await syncAfterConnect()
    await flushTimers()

    expect(api.syncMessages.mock.calls.map((call) => call[0])).toEqual(['old-cursor', 'page-1'])
    expect(getSyncCursor(ME)).toBe('page-2')
    expect(store().messages['c1'].map((m) => m.id)).toEqual(['already', 's1', 's2'])
  })

  it('acknowledges delivery only for incoming messages that were not acknowledged yet', async () => {
    api.syncMessages.mockResolvedValue({
      items: [
        makeMessage({ id: 'new-in' }), // from the other person, not acknowledged
        makeMessage({ id: 'old-in', myReceipt: 'DELIVERED' }), // already acknowledged
        makeMessage({ id: 'my-own', senderId: ME }), // sent by me from another device
      ],
      nextCursor: 'c',
      hasMore: false,
    })

    await syncAfterConnect()
    await flushTimers()

    expect(api.markDelivered).toHaveBeenCalledTimes(1)
    expect(api.markDelivered).toHaveBeenCalledWith(['new-in'])
  })

  it('then refreshes the chat list, so unread counts come from the server', async () => {
    api.syncMessages.mockResolvedValue({ items: [], nextCursor: 'c', hasMore: false })
    api.getSummaries.mockResolvedValue([{ conversationId: 'c1', unreadCount: 4, lastMessage: null }])

    await syncAfterConnect()

    expect(api.getConversations).toHaveBeenCalledTimes(1)
    expect(store().summaries['c1'].unreadCount).toBe(4)
  })

  it('runs on every (re)connect', async () => {
    api.syncMessages.mockResolvedValue({ items: [], nextCursor: 'c', hasMore: false })

    live.fireConnected()
    await flushTimers()
    live.fireConnected()
    await flushTimers()

    expect(api.syncMessages).toHaveBeenCalledTimes(2)
  })

  it('survives a failing request (tries again on the next reconnect)', async () => {
    api.syncMessages.mockRejectedValue(new Error('offline'))

    await expect(syncAfterConnect()).resolves.toBeUndefined()
    expect(api.getConversations).toHaveBeenCalled() // still refreshes the list
  })

  it('marks the open conversation as read after catching up (visible tab)', async () => {
    store().setOpenConversation('c1')
    api.syncMessages.mockResolvedValue({ items: [makeMessage({ id: 'x1' })], nextCursor: 'c', hasMore: false })
    api.getSummaries.mockResolvedValue([{ conversationId: 'c1', unreadCount: 1, lastMessage: null }])

    await syncAfterConnect()
    await flushTimers()

    expect(api.markRead).toHaveBeenCalledWith('c1', 'x1')
  })
})

describe('SYSTEM messages (group changes)', () => {
  const left = (id: string, conversationId = 'g1') =>
    makeSystemMessage({ event: 'MEMBER_LEFT', actor: { id: OTHER, name: 'Ravi Kumar' } }, { id, conversationId })

  beforeEach(() => {
    store().upsertConversation(makeGroup())
  })

  it('are shown, but never acknowledged and never unread; the group is loaded again ONCE for several of them', async () => {
    const updated = makeGroup({ participants: [] })
    api.getConversation.mockResolvedValue(updated)

    live.send('/user/queue/messages', created(left('s1')))
    live.send('/user/queue/messages', created(left('s2')))
    await flushTimers()

    expect(store().messages['g1'].map((m) => m.id)).toEqual(['s1', 's2'])
    expect(store().summaries['g1'].unreadCount).toBe(0)
    expect(api.markDelivered).not.toHaveBeenCalled()
    expect(api.getConversation).toHaveBeenCalledTimes(1)
    expect(api.getConversation).toHaveBeenCalledWith('g1')
    expect(store().conversations['g1'].participants).toEqual([])
  })

  it('in the open group: no read request because of them', async () => {
    api.getConversation.mockResolvedValue(makeGroup())
    store().setOpenConversation('g1')

    live.send('/user/queue/messages', created(left('s3')))
    await flushTimers()

    expect(api.markRead).not.toHaveBeenCalled()
  })

  it('for a group I do not know yet (I was just added): the chat list is loaded again', async () => {
    api.getConversations.mockResolvedValue([makeGroup({ id: 'g-new' })])

    live.send(
      '/user/queue/messages',
      created(
        makeSystemMessage(
          { event: 'MEMBERS_ADDED', actor: { id: OTHER, name: 'Ravi' }, users: [{ id: ME, name: 'Asha' }] },
          { conversationId: 'g-new' },
        ),
      ),
    )
    await flushTimers()

    expect(api.getConversations).toHaveBeenCalled()
    expect(store().conversations['g-new']).toBeDefined()
    expect(api.getConversation).not.toHaveBeenCalled()
  })

  it('are not acknowledged when they arrive through sync either', async () => {
    api.syncMessages.mockResolvedValue({
      items: [left('s4'), makeMessage({ id: 'real', conversationId: 'g1' })],
      nextCursor: 'c',
      hasMore: false,
    })

    await syncAfterConnect()
    await flushTimers()

    expect(api.markDelivered).toHaveBeenCalledWith(['real'])
  })
})

describe('typing in a group', () => {
  const typing = (userId: string, on: boolean) => ({
    type: 'TYPING', eventId: 'e', conversationId: 'g1', data: { conversationId: 'g1', userId, typing: on }, timestamp: '',
  })

  it('tracks each person separately, each with their own expiry', () => {
    live.send('/user/queue/typing', typing(OTHER, true))
    vi.advanceTimersByTime(3000)
    live.send('/user/queue/typing', typing(THIRD, true))
    expect(store().typing['g1']).toEqual([OTHER, THIRD])

    vi.advanceTimersByTime(2100) // OTHER's 5 s are over, THIRD's not
    expect(store().typing['g1']).toEqual([THIRD])

    live.send('/user/queue/typing', typing(THIRD, false))
    expect(store().typing['g1']).toEqual([])
  })
})

describe('desktop notifications for a new message', () => {
  const popups: Array<{ title: string; options: NotificationOptions }> = []

  beforeEach(() => {
    popups.length = 0
    // A fake browser Notification that is allowed, and the user chose "on" in Settings.
    class FakeNotification {
      static permission = 'granted'
      onclick: (() => void) | null = null
      constructor(title: string, options: NotificationOptions) {
        popups.push({ title, options })
      }
      close() {}
    }
    vi.stubGlobal('Notification', FakeNotification)
    localStorage.setItem('chat-app-desktop-notifications', 'true')
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    localStorage.clear()
  })

  it('shows a pop-up for a message from the other person while the tab is hidden', () => {
    setVisibility('hidden')

    live.send('/user/queue/messages', created(makeMessage({ id: 'n1', content: 'Are you there?' })))

    expect(popups).toHaveLength(1)
    expect(popups[0].options).toMatchObject({ body: 'Are you there?', tag: 'c1' })
  })

  it('shows nothing while the tab is visible', () => {
    live.send('/user/queue/messages', created(makeMessage({ id: 'n2' })))
    expect(popups).toHaveLength(0)
  })

  it('shows nothing for my own message (sent from another device)', () => {
    setVisibility('hidden')
    live.send('/user/queue/messages', created(makeMessage({ id: 'n3', senderId: ME })))
    expect(popups).toHaveLength(0)
  })

  it('shows nothing when the chat is muted', () => {
    setVisibility('hidden')
    store().upsertConversation(
      makeConversation({ preferences: { mutedUntil: new Date(Date.now() + 3_600_000).toISOString(), archived: false, pinned: false } }),
    )

    live.send('/user/queue/messages', created(makeMessage({ id: 'n4' })))

    expect(popups).toHaveLength(0)
  })

  it('shows nothing when the user did not turn it on, and one message is shown only once', () => {
    setVisibility('hidden')
    localStorage.removeItem('chat-app-desktop-notifications')
    live.send('/user/queue/messages', created(makeMessage({ id: 'n5' })))
    expect(popups).toHaveLength(0)

    localStorage.setItem('chat-app-desktop-notifications', 'true')
    live.send('/user/queue/messages', created(makeMessage({ id: 'n6' })))
    live.send('/user/queue/messages', created(makeMessage({ id: 'n6' }))) // the same message again
    expect(popups).toHaveLength(1)
  })
})
