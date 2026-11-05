import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { ME, OTHER, THIRD, makeGroup, makeMessage, makeSystemMessage } from '../../test/chatFixtures'
import type { ChatMessage } from '../../types/chat'
import MessageList from './MessageList'

function renderList(messages: ChatMessage[], extra: Partial<Parameters<typeof MessageList>[0]> = {}) {
  const onRetry = vi.fn()
  render(
    <MessageList
      messages={messages}
      currentUserId={ME}
      hasMore={false}
      loadingOlder={false}
      firstUnreadId={null}
      unreadCount={0}
      onLoadOlder={vi.fn()}
      onRetry={onRetry}
      {...extra}
    />,
  )
  return { onRetry }
}

const today = new Date()
const yesterday = new Date(Date.now() - 24 * 60 * 60 * 1000)
const at = (date: Date, seconds: number) => new Date(date.getFullYear(), date.getMonth(), date.getDate(), 12, 0, seconds).toISOString()

describe('MessageList', () => {
  it('draws one date separator per day', () => {
    renderList([
      makeMessage({ id: 'a', content: 'old one', createdAt: at(yesterday, 1) }),
      makeMessage({ id: 'b', content: 'old two', createdAt: at(yesterday, 2) }),
      makeMessage({ id: 'c', content: 'fresh', createdAt: at(today, 3) }),
    ])

    expect(screen.getAllByText('Yesterday')).toHaveLength(1)
    expect(screen.getAllByText('Today')).toHaveLength(1)
    expect(screen.getAllByTestId('message')).toHaveLength(3)
  })

  it('shows ticks only on my messages, matching their status', () => {
    renderList([
      makeMessage({ id: 'in', content: 'from them', createdAt: at(today, 1) }),
      makeMessage({ id: 's', senderId: ME, status: 'SENT', createdAt: at(today, 2) }),
      makeMessage({ id: 'd', senderId: ME, status: 'DELIVERED', createdAt: at(today, 3) }),
      makeMessage({ id: 'r', senderId: ME, status: 'READ', createdAt: at(today, 4) }),
    ])

    expect(screen.getAllByLabelText('Sent')).toHaveLength(1)
    expect(screen.getAllByLabelText('Delivered')).toHaveLength(1)
    expect(screen.getAllByLabelText('Read')).toHaveLength(1)
    expect(screen.getAllByRole('img')).toHaveLength(3) // nothing on the incoming message
  })

  it('shows a clock while sending, and a red mark with a Retry button when sending failed', async () => {
    const sending: ChatMessage = { ...makeMessage({ id: 'l1', senderId: ME, content: 'one', createdAt: at(today, 1) }), localStatus: 'SENDING', status: null, idempotencyKey: 'k1' }
    const failed: ChatMessage = {
      ...makeMessage({ id: 'l2', senderId: ME, content: 'two', createdAt: at(today, 2) }),
      localStatus: 'FAILED', status: null, idempotencyKey: 'k2', errorMessage: 'No connection. Press Retry when you are back online.',
    }
    const { onRetry } = renderList([sending, failed])

    expect(screen.getByLabelText('Sending')).toBeInTheDocument()
    expect(screen.getByLabelText('Failed to send')).toBeInTheDocument()
    expect(screen.getByText(/No connection/)).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Retry' }))
    expect(onRetry).toHaveBeenCalledWith(failed)
  })

  it('puts the "N unread messages" divider above the first unread message', () => {
    renderList(
      [
        makeMessage({ id: 'read', content: 'already read', createdAt: at(today, 1) }),
        makeMessage({ id: 'u1', content: 'unread one', createdAt: at(today, 2) }),
        makeMessage({ id: 'u2', content: 'unread two', createdAt: at(today, 3) }),
      ],
      { firstUnreadId: 'u1', unreadCount: 2 },
    )

    const divider = screen.getByText('2 unread messages')
    const texts = screen.getAllByText(/already read|unread one|unread two|unread messages/).map((node) => node.textContent)
    expect(texts).toEqual(['already read', '2 unread messages', 'unread one', 'unread two'])
    expect(divider).toBeInTheDocument()
  })

  it('has no divider without unread messages', () => {
    renderList([makeMessage({ id: 'a', senderId: OTHER })], { firstUnreadId: 'a', unreadCount: 0 })

    expect(screen.queryByText(/unread/)).not.toBeInTheDocument()
  })

  it('tells the user when the whole history is loaded and while older messages load', () => {
    renderList([makeMessage({ id: 'a' })], { hasMore: false })
    expect(screen.getByText(/start of your conversation/)).toBeInTheDocument()
  })

  it('shows a loading line while older messages are fetched', () => {
    renderList([makeMessage({ id: 'a' })], { hasMore: true, loadingOlder: true })

    expect(screen.getByText(/Loading older messages/)).toBeInTheDocument()
    expect(screen.queryByText(/start of your conversation/)).not.toBeInTheDocument()
  })

  it('asks for older messages when scrolled to the top, but only if there are some', () => {
    const onLoadOlder = vi.fn()
    renderList([makeMessage({ id: 'a' })], { hasMore: true, onLoadOlder })

    screen.getByRole('log').dispatchEvent(new Event('scroll')) // jsdom: scrollTop is 0 = top

    expect(onLoadOlder).toHaveBeenCalledTimes(1)
  })

  it('does not ask again when nothing older exists', () => {
    const onLoadOlder = vi.fn()
    renderList([makeMessage({ id: 'a' })], { hasMore: false, onLoadOlder })

    screen.getByRole('log').dispatchEvent(new Event('scroll'))

    expect(onLoadOlder).not.toHaveBeenCalled()
  })
})

describe('MessageList in a group', () => {
  const group = makeGroup()

  it('names the sender above the first message of each run, not on my messages', () => {
    renderList(
      [
        makeMessage({ id: 'a', senderId: OTHER, content: 'one', createdAt: at(today, 1) }),
        makeMessage({ id: 'b', senderId: OTHER, content: 'two', createdAt: at(today, 2) }),
        makeMessage({ id: 'c', senderId: THIRD, content: 'three', createdAt: at(today, 3) }),
        makeMessage({ id: 'd', senderId: ME, content: 'four', createdAt: at(today, 4) }),
      ],
      { conversation: group },
    )

    const bubbles = screen.getAllByTestId('message')
    expect(bubbles[0]).toHaveTextContent('Ravi Kumar')
    expect(bubbles[1]).not.toHaveTextContent('Ravi Kumar')
    expect(bubbles[2]).toHaveTextContent('Meena Iyer')
    expect(bubbles[3]).not.toHaveTextContent('Asha Rao')
  })

  it('a former member is named "Former member"', () => {
    renderList([makeMessage({ id: 'a', senderId: 'u-gone', content: 'bye', createdAt: at(today, 1) })], { conversation: group })

    expect(screen.getByTestId('message')).toHaveTextContent('Former member')
  })

  it('draws SYSTEM messages as centred notes (not bubbles)', () => {
    renderList(
      [makeSystemMessage({ event: 'MEMBER_LEFT', actor: { id: OTHER, name: 'Ravi Kumar' } }, { createdAt: at(today, 1) })],
      { conversation: group },
    )

    expect(screen.getByTestId('system-message')).toHaveTextContent('Ravi Kumar left')
    expect(screen.queryByTestId('message')).not.toBeInTheDocument()
  })

  it('offers "Message info" on my sent messages only (not while sending, not in private chats)', async () => {
    const onShowInfo = vi.fn()
    const mine = makeMessage({ id: 'm', senderId: ME, content: 'mine', createdAt: at(today, 1) })
    const sending: ChatMessage = { ...makeMessage({ id: 'l', senderId: ME, createdAt: at(today, 2) }), localStatus: 'SENDING' }
    renderList([mine, sending, makeMessage({ id: 't', senderId: OTHER, createdAt: at(today, 3) })], { conversation: group, onShowInfo })

    const buttons = screen.getAllByRole('button', { name: 'Message info' })
    expect(buttons).toHaveLength(1)
    await userEvent.click(buttons[0])
    expect(onShowInfo).toHaveBeenCalledWith(mine)
  })

  it('private chats: no sender names and no info button', () => {
    renderList([makeMessage({ id: 'x', senderId: OTHER, content: 'hi' }), makeMessage({ id: 'y', senderId: ME })], { onShowInfo: vi.fn() })

    expect(screen.getAllByTestId('message')[0]).not.toHaveTextContent('Ravi Kumar')
    expect(screen.queryByRole('button', { name: 'Message info' })).not.toBeInTheDocument()
  })
})
