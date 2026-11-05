import { ArrowLeft, Info, X } from 'lucide-react'
import { useCallback, useEffect, useRef, useState, type DragEvent, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import CallButtons from '../calls/CallButtons'
import ChatOptionsMenu from './ChatOptionsMenu'
import Composer, { type ComposerHandle } from './Composer'
import ConversationAvatar from './ConversationAvatar'
import GroupInfoPanel from './GroupInfoPanel'
import MessageInfoDialog from './MessageInfoDialog'
import MessageList from './MessageList'
import PresenceStatus from './PresenceStatus'
import { ensureConversation, loadLatestMessages, loadOlderMessages, retryMessage, sendMessage } from '../../features/messaging/chatActions'
import {
  conversationTitle,
  isIncoming,
  memberNamesLine,
  sendBlockReason,
  typingText,
} from '../../features/messaging/conversationInfo'
import { cancelAttachment, isUploadPending, retryAttachment, sendAttachment } from '../../features/media/attachmentActions'
import { scheduleRead } from '../../features/messaging/realtime'
import { refreshUnreadCountAfterOpeningChat } from '../../features/notifications/notificationActions'
import { toAppError } from '../../services/api/appError'
import { useAuthStore } from '../../store/authStore'
import { useChatStore } from '../../store/chatStore'
import type { ChatMessage } from '../../types/chat'

const NO_MESSAGES: ChatMessage[] = []
const NOBODY_TYPING: string[] = []

// One open conversation: header, messages, "typing...", composer (or why I cannot write).
// Groups also have an info panel (members, settings) and "Message info" for my messages.
// The page gives it a `key` = conversationId, so all its state starts fresh for each conversation.
export default function ChatWindow({ conversationId }: { conversationId: string }) {
  const meId = useAuthStore((state) => state.user?.id ?? '')
  const conversation = useChatStore((state) => state.conversations[conversationId])
  const conversationsLoaded = useChatStore((state) => state.conversationsLoaded)
  const messages = useChatStore((state) => state.messages[conversationId]) ?? NO_MESSAGES
  const paging = useChatStore((state) => state.paging[conversationId])
  const typingUsers = useChatStore((state) => state.typing[conversationId]) ?? NOBODY_TYPING

  const [error, setError] = useState<string | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [showInfo, setShowInfo] = useState(false)
  const [dragging, setDragging] = useState(false)
  const composerRef = useRef<ComposerHandle>(null)
  const [infoMessage, setInfoMessage] = useState<ChatMessage | null>(null)
  // How many unread messages the conversation had when it was opened (null = not known yet).
  const [unreadAtOpen, setUnreadAtOpen] = useState<number | null>(null)
  // The message the "N unread" divider goes above. Decided once, so it does not move while I read.
  const [firstUnreadId, setFirstUnreadId] = useState<string | null | undefined>(undefined)

  // Open: tell the store which chat I look at, load the conversation and its newest messages.
  useEffect(() => {
    useChatStore.getState().setOpenConversation(conversationId)
    let cancelled = false
    ensureConversation(conversationId)
      .then(() => loadLatestMessages(conversationId))
      .catch((caught) => {
        if (!cancelled) setError(toAppError(caught).message)
      })
    return () => {
      cancelled = true
      useChatStore.getState().setOpenConversation(null)
    }
  }, [conversationId])

  // The server marks this chat's notification as read a moment after my read receipt: update the bell later.
  useEffect(() => refreshUnreadCountAfterOpeningChat(), [conversationId])

  // Remember how many messages were unread when I opened the chat.
  useEffect(() => {
    if (conversationsLoaded && unreadAtOpen === null) {
      setUnreadAtOpen(useChatStore.getState().summaries[conversationId]?.unreadCount ?? 0)
    }
  }, [conversationsLoaded, unreadAtOpen, conversationId])

  // Once the newest messages are here: place the unread divider and mark the chat as read.
  // SYSTEM messages ("Asha added Ravi") never count as unread.
  const loaded = paging?.loaded ?? false
  useEffect(() => {
    if (!loaded || unreadAtOpen === null) return
    if (firstUnreadId === undefined) {
      const incoming =
        useChatStore.getState().messages[conversationId]?.filter((message) => isIncoming(message, meId)) ?? []
      setFirstUnreadId(unreadAtOpen > 0 ? (incoming[Math.max(0, incoming.length - unreadAtOpen)]?.id ?? null) : null)
    }
    scheduleRead(conversationId)
  }, [loaded, unreadAtOpen, firstUnreadId, conversationId, meId])

  const handleLoadOlder = useCallback(() => {
    loadOlderMessages(conversationId).catch(() => undefined)
  }, [conversationId])

  const handleRetry = useCallback(
    (message: ChatMessage) => {
      const key = message.idempotencyKey
      if (!key) return
      // A message with a file goes through its upload (which skips the upload if it already worked).
      if (isUploadPending(key)) void retryAttachment(key)
      else void retryMessage(conversationId, key)
    },
    [conversationId],
  )

  const handleCancel = useCallback((message: ChatMessage) => {
    if (message.idempotencyKey) cancelAttachment(message.idempotencyKey)
  }, [])

  // A file dropped anywhere on the chat window becomes the attachment of the composer.
  function handleDrop(event: DragEvent) {
    event.preventDefault()
    setDragging(false)
    const file = event.dataTransfer.files[0]
    if (file) composerRef.current?.attachFile(file)
  }
  function hasFiles(event: DragEvent) {
    return Array.from(event.dataTransfer.types).includes('Files')
  }

  if (error) {
    return (
      <div className="flex flex-1 flex-col items-center justify-center gap-3 p-6 text-center">
        <p role="alert">{error}</p>
        <Link to="/chats" className="text-brand-600 hover:underline">
          Back to chats
        </Link>
      </div>
    )
  }

  const group = conversation?.type === 'GROUP'
  const typing = typingText(conversation, typingUsers, meId)
  // Group: show who is typing, otherwise the members.
  // Private chat: "typing...", otherwise "online" / "last seen ..." (nothing when the person's privacy hides it).
  const subtitle: ReactNode =
    typing ||
    (group && conversation ? (
      memberNamesLine(conversation, meId)
    ) : conversation?.type === 'PRIVATE' ? (
      <PresenceStatus userId={conversation.otherUser?.id} />
    ) : (
      ''
    ))
  const blocked = conversation ? sendBlockReason(conversation) : null

  return (
    <div className="flex min-h-0 min-w-0 flex-1">
      <div
        className="relative flex min-h-0 min-w-0 flex-1 flex-col"
        onDragOver={(event) => {
          if (!blocked && hasFiles(event)) {
            event.preventDefault()
            setDragging(true)
          }
        }}
        onDragLeave={(event) => {
          if (event.currentTarget === event.target) setDragging(false)
        }}
        onDrop={(event) => {
          if (!blocked && hasFiles(event)) handleDrop(event)
        }}
      >
        {dragging && (
          <div className="pointer-events-none absolute inset-0 z-30 flex items-center justify-center bg-brand-50/90 text-lg font-medium text-brand-700 dark:bg-gray-900/90 dark:text-brand-300">
            Drop a file to send it
          </div>
        )}
        <header className="glass z-10 flex shrink-0 items-center gap-3 border-b border-gray-200/80 px-3 py-2.5 sm:px-5 dark:border-gray-800">
          <Link to="/chats" aria-label="Back to chats" className="rounded-xl p-2 hover:bg-gray-100 md:hidden dark:hover:bg-gray-800">
            <ArrowLeft size={20} />
          </Link>
          {conversation ? <ConversationAvatar conversation={conversation} size={42} /> : null}
          <div className="min-w-0 flex-1">
            <h1 className="truncate text-base font-semibold">{conversation ? conversationTitle(conversation) : 'Loading...'}</h1>
            <p className={`flex h-4 items-center gap-1.5 truncate text-xs ${typing ? 'font-medium text-brand-600 dark:text-brand-300' : 'text-gray-500 dark:text-gray-400'}`} aria-live="polite">
              {typing && (
                <span aria-hidden="true" className="flex gap-0.5">
                  <span className="h-1 w-1 animate-dots rounded-full bg-current" />
                  <span className="h-1 w-1 animate-dots rounded-full bg-current [animation-delay:150ms]" />
                  <span className="h-1 w-1 animate-dots rounded-full bg-current [animation-delay:300ms]" />
                </span>
              )}
              <span className="truncate">{subtitle}</span>
            </p>
          </div>
          {group && (
            <button
              type="button"
              onClick={() => setShowInfo((value) => !value)}
              aria-label="Group info"
              aria-pressed={showInfo}
              className="rounded-xl p-2 text-gray-500 hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-gray-800"
            >
              <Info size={20} />
            </button>
          )}
          {conversation?.type === 'PRIVATE' && conversation.otherUser && <CallButtons user={conversation.otherUser} />}
          {conversation && <ChatOptionsMenu conversation={conversation} onError={setActionError} />}
        </header>

        {actionError && (
          <div role="alert" className="flex items-start gap-2 bg-red-50 px-3 py-2 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">
            <span className="flex-1">{actionError}</span>
            <button type="button" onClick={() => setActionError(null)} aria-label="Dismiss" className="rounded hover:bg-red-100 dark:hover:bg-red-900">
              <X size={16} />
            </button>
          </div>
        )}

        <MessageList
          messages={messages}
          currentUserId={meId}
          hasMore={paging?.hasMore ?? false}
          loadingOlder={paging?.loadingOlder ?? false}
          firstUnreadId={firstUnreadId ?? null}
          unreadCount={unreadAtOpen ?? 0}
          onLoadOlder={handleLoadOlder}
          onRetry={handleRetry}
          onCancel={handleCancel}
          conversation={conversation}
          onShowInfo={setInfoMessage}
        />

        {blocked ? (
          <p
            role="status"
            className="border-t border-gray-200/80 bg-white px-4 py-4 text-center text-sm text-gray-600 dark:border-gray-800 dark:bg-gray-900 dark:text-gray-300"
          >
            {blocked === 'NOT_A_MEMBER'
              ? "You can't send messages to this group because you're no longer a participant."
              : 'Only admins can send messages.'}
          </p>
        ) : (
          <Composer
            ref={composerRef}
            conversationId={conversationId}
            onSend={(text) => void sendMessage(conversationId, text)}
            onSendAttachment={(input, onRejected) => void sendAttachment(conversationId, input, onRejected)}
          />
        )}
      </div>

      {showInfo && conversation && group && (
        <GroupInfoPanel conversation={conversation} meId={meId} onClose={() => setShowInfo(false)} />
      )}
      {infoMessage && (
        <MessageInfoDialog message={infoMessage} conversation={conversation} onClose={() => setInfoMessage(null)} />
      )}
    </div>
  )
}
