import { ME, makeMessage } from '../test/chatFixtures'
import type { ChatMessage } from '../types/chat'
import { useAuthStore } from './authStore'
import { useChatStore } from './chatStore'

function pending(key: string, content: string): ChatMessage {
  return {
    ...makeMessage({ id: `local-${key}`, senderId: ME, content, createdAt: '2026-11-04T10:00:00.000Z' }),
    localStatus: 'SENDING',
    idempotencyKey: key,
  }
}

beforeEach(() => {
  useChatStore.getState().reset()
  useAuthStore.setState({ ...useAuthStore.getState(), user: { id: ME, username: 'asha', displayName: 'Asha', email: 'a@x.com' } })
})

describe('removePending', () => {
  it('removes only the pending message with that key', () => {
    const store = useChatStore.getState()
    store.addServerMessages('c1', [makeMessage({ id: 'old', content: 'old' })])
    store.addPending(pending('k1', 'one'))
    store.addPending(pending('k2', 'two'))

    useChatStore.getState().removePending('c1', 'k1')

    expect(useChatStore.getState().messages['c1'].map((m) => m.content)).toEqual(['old', 'two'])
  })

  it('never removes a saved message, even if the key is unknown', () => {
    const store = useChatStore.getState()
    store.addServerMessages('c1', [makeMessage({ id: 'srv', senderId: ME })])

    useChatStore.getState().removePending('c1', 'k-unknown')

    expect(useChatStore.getState().messages['c1']).toHaveLength(1)
  })

  it('puts the chat list preview back to the previous message', () => {
    const store = useChatStore.getState()
    store.addServerMessages('c1', [makeMessage({ id: 'old', content: 'old news', createdAt: '2026-11-04T09:00:00.000Z' })])
    store.addPending(pending('k1', 'cancelled photo'))
    expect(useChatStore.getState().summaries['c1'].lastMessage?.content).toBe('cancelled photo')

    useChatStore.getState().removePending('c1', 'k1')

    expect(useChatStore.getState().summaries['c1'].lastMessage?.content).toBe('old news')
  })

  it('leaves no preview when it was the only message', () => {
    useChatStore.getState().addPending(pending('k1', 'only one'))

    useChatStore.getState().removePending('c1', 'k1')

    expect(useChatStore.getState().summaries['c1'].lastMessage).toBeNull()
  })
})
