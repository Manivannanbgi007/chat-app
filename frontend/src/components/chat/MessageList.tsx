import { ArrowDown, Loader2 } from 'lucide-react'
import { Fragment, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { formatDayLabel } from '../../features/messaging/conversationList'
import { currentName, senderColourClass } from '../../features/messaging/conversationInfo'
import { describeSystemMessage } from '../../features/messaging/systemMessages'
import type { ChatMessage, Conversation } from '../../types/chat'
import MessageBubble from './MessageBubble'

// The scrolling list of messages (a plain list, not virtualized: older messages are loaded page by page
// when you scroll to the top, so the list stays short).
//
// Scrolling rules:
// - when the chat opens, jump to the newest message
// - a new message at the bottom: follow it if you are already at the bottom (or it is mine),
//   otherwise show the "new messages" button with a counter
// - older messages added at the top keep the message you were looking at in the same place
//
// Groups: SYSTEM messages ("Asha added Ravi") are centred notes, and other people's messages show the
// sender's name above the first message of each run of messages from the same person.
const NEAR_BOTTOM_PX = 80
const NEAR_TOP_PX = 80

export default function MessageList({
  messages,
  currentUserId,
  hasMore,
  loadingOlder,
  firstUnreadId,
  unreadCount,
  onLoadOlder,
  onRetry,
  onCancel,
  conversation,
  onShowInfo,
}: {
  messages: ChatMessage[]
  currentUserId: string
  hasMore: boolean
  loadingOlder: boolean
  // The message above which the "N unread messages" divider is drawn (null = none).
  firstUnreadId: string | null
  unreadCount: number
  onLoadOlder: () => void
  onRetry: (message: ChatMessage) => void
  // Cancel an upload / discard a failed media message
  onCancel?: (message: ChatMessage) => void
  // The chat (for names in groups and SYSTEM messages). Optional: without it the list behaves like a private chat.
  conversation?: Conversation
  // "Message info" for my messages (groups)
  onShowInfo?: (message: ChatMessage) => void
}) {
  const group = conversation?.type === 'GROUP'
  const containerRef = useRef<HTMLDivElement>(null)
  const atBottomRef = useRef(true)
  const previousFirstId = useRef<string | null>(null)
  const previousLastId = useRef<string | null>(null)
  // Remembers the scroll position just before older messages are loaded.
  const savedScroll = useRef<{ height: number; top: number } | null>(null)

  const [showJump, setShowJump] = useState(false)
  const [newBelow, setNewBelow] = useState(0)

  function scrollToBottom() {
    const element = containerRef.current
    if (element) element.scrollTop = element.scrollHeight
    atBottomRef.current = true
    setShowJump(false)
    setNewBelow(0)
  }

  function handleScroll() {
    const element = containerRef.current
    if (!element) return
    const atBottom = element.scrollHeight - element.scrollTop - element.clientHeight < NEAR_BOTTOM_PX
    atBottomRef.current = atBottom
    setShowJump(!atBottom)
    if (atBottom) setNewBelow(0)

    if (element.scrollTop < NEAR_TOP_PX && hasMore && !loadingOlder) {
      savedScroll.current = { height: element.scrollHeight, top: element.scrollTop }
      onLoadOlder()
    }
  }

  // Runs after every change of the message list, before the browser paints.
  useLayoutEffect(() => {
    const element = containerRef.current
    const first = messages[0]?.id ?? null
    const last = messages[messages.length - 1]

    if (element && savedScroll.current && first !== previousFirstId.current && previousFirstId.current !== null) {
      // Older messages were added on top: keep the same message in view.
      element.scrollTop = element.scrollHeight - savedScroll.current.height + savedScroll.current.top
      savedScroll.current = null
    } else if (last && last.id !== previousLastId.current) {
      const firstShow = previousLastId.current === null
      if (firstShow || atBottomRef.current || last.senderId === currentUserId) {
        scrollToBottom()
      } else {
        setNewBelow((count) => count + 1)
        setShowJump(true)
      }
    }
    previousFirstId.current = first
    previousLastId.current = last?.id ?? null
    // Only a change of the list itself should trigger scrolling.
  }, [messages, currentUserId])

  // Which messages start a new day (they get a date separator above them).
  const startsNewDay = useMemo(() => {
    const dayOf = (message: ChatMessage) => new Date(message.createdAt).toDateString()
    return messages.map((message, index) => index === 0 || dayOf(message) !== dayOf(messages[index - 1]))
  }, [messages])

  return (
    <div className="relative min-h-0 flex-1">
      <div
        ref={containerRef}
        onScroll={handleScroll}
        role="log"
        aria-label="Messages"
        className="chat-wallpaper h-full space-y-1.5 overflow-y-auto px-3 py-4 sm:px-6 lg:px-10 xl:px-16"
      >
        {loadingOlder && (
          <p className="flex items-center justify-center gap-2 text-sm text-gray-500">
            <Loader2 className="animate-spin" size={16} /> Loading older messages...
          </p>
        )}
        {!hasMore && messages.length > 0 && (
          <p className="text-center text-xs text-gray-400">This is the start of your conversation.</p>
        )}

        {messages.map((message, index) => {
          const previous = messages[index - 1]
          const mine = message.senderId === currentUserId
          // In a group, name the sender when a new run of their messages starts.
          const showSender =
            group &&
            !mine &&
            message.type !== 'SYSTEM' &&
            (!previous || previous.type === 'SYSTEM' || previous.senderId !== message.senderId || startsNewDay[index])
          const sender = showSender
            ? { name: currentName(conversation, message.senderId) ?? 'Former member', colourClass: senderColourClass(message.senderId) }
            : null

          return (
            <Fragment key={message.id}>
              {startsNewDay[index] && (
                <div className="flex justify-center">
                  <span className="my-2 animate-fade-in rounded-full bg-white/90 px-3.5 py-1 text-xs font-medium text-gray-600 shadow-sm backdrop-blur dark:bg-gray-800/90 dark:text-gray-300">
                    {formatDayLabel(message.createdAt)}
                  </span>
                </div>
              )}
              {message.id === firstUnreadId && unreadCount > 0 && (
                <div className="flex justify-center">
                  <span className="my-2 animate-pop rounded-full bg-brand-600 px-3.5 py-1 text-xs font-medium text-white shadow-glow">
                    {unreadCount} unread {unreadCount === 1 ? 'message' : 'messages'}
                  </span>
                </div>
              )}
              {message.type === 'SYSTEM' ? (
                <div className="flex justify-center" data-testid="system-message">
                  <span className="my-1 max-w-[90%] animate-fade-in rounded-full bg-white/80 px-3.5 py-1 text-center text-xs text-gray-600 shadow-sm backdrop-blur dark:bg-gray-800/80 dark:text-gray-300">
                    {describeSystemMessage(message, conversation, currentUserId)}
                  </span>
                </div>
              ) : (
                <MessageBubble
                  message={message}
                  mine={mine}
                  onRetry={onRetry}
                  onCancel={onCancel}
                  sender={sender}
                  onShowInfo={group ? onShowInfo : undefined}
                />
              )}
            </Fragment>
          )
        })}
      </div>

      {showJump && (
        <button
          type="button"
          onClick={scrollToBottom}
          aria-label={newBelow > 0 ? `Scroll to latest, ${newBelow} new` : 'Scroll to latest'}
          className="absolute bottom-4 right-4 flex animate-pop items-center gap-1 rounded-full bg-white px-3 py-2 text-sm shadow-float hover:bg-gray-50 dark:bg-gray-800 dark:hover:bg-gray-700"
        >
          <ArrowDown size={18} />
          {newBelow > 0 && (
            <span className="rounded-full bg-brand-600 px-2 text-xs font-semibold text-white">{newBelow}</span>
          )}
        </button>
      )}
    </div>
  )
}
