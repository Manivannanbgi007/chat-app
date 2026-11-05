// Connects the WebSocket events to the chat store, and catches up after every (re)connect.
//
// Live events:  /user/queue/messages  -> new message (mine from another device, from someone else, or a SYSTEM
//                                         message about a group change)
//               /user/queue/receipts  -> my message was delivered / read
//               /user/queue/acks      -> answer to a message sent over STOMP (we send with REST, kept for completeness)
//               /user/queue/errors    -> a rejected frame
//               /user/queue/typing    -> someone is typing in one of my conversations
import * as chatApi from '../../services/api/chatApi'
import { useAuthStore } from '../../store/authStore'
import { useChatStore } from '../../store/chatStore'
import type { Message, RealtimeEvent } from '../../types/chat'
import { messageForSendError, refreshConversation, refreshConversationList } from './chatActions'
import { isIncoming } from './conversationInfo'
import { getSyncCursor, saveSyncCursor } from './cursorStorage'
import { showDesktopNotificationFor } from '../notifications/localNotifications'

// The part of the WebSocket manager this file needs (tests pass a fake).
export interface RealtimeConnection {
  subscribe(destination: string, handler: (event: unknown) => void): () => void
  onConnected(listener: () => void): () => void
}

const ACK_DELAY_MS = 200 // acknowledgements of messages that arrive together are sent as one request
const TYPING_EXPIRES_MS = 5000 // if no "stopped typing" arrives we hide the indicator anyway

// ---- small helpers ----

function me(): string {
  return useAuthStore.getState().user?.id ?? ''
}

// "Am I looking at this conversation right now?" (open AND the tab is visible)
function isReading(conversationId: string): boolean {
  return (
    useChatStore.getState().openConversationId === conversationId && document.visibilityState === 'visible'
  )
}

// ---- delivered / read acknowledgements ----

const pendingDelivered = new Set<string>()
let deliveredTimer: ReturnType<typeof setTimeout> | null = null
const readTimers = new Map<string, ReturnType<typeof setTimeout>>()
const typingTimers = new Map<string, ReturnType<typeof setTimeout>>() // key: conversationId + userId
const refreshTimers = new Map<string, ReturnType<typeof setTimeout>>()

// "This device received the message." Batched: one request for everything that arrives within 200 ms.
export function queueDelivered(messageId: string): void {
  pendingDelivered.add(messageId)
  scheduleDeliveredFlush()
}

function scheduleDeliveredFlush(): void {
  if (deliveredTimer) return
  deliveredTimer = setTimeout(() => {
    deliveredTimer = null
    const ids = [...pendingDelivered].slice(0, 100) // the server accepts at most 100 ids per request
    ids.forEach((id) => pendingDelivered.delete(id))
    if (ids.length > 0) {
      chatApi.markDelivered(ids).catch(() => undefined) // not critical: the next sync / open tries again
    }
    if (pendingDelivered.size > 0) {
      scheduleDeliveredFlush() // more than 100 were waiting
    }
  }, ACK_DELAY_MS)
}

// Is there anything in this conversation that I received but have not marked as read?
function hasUnreadIncoming(conversationId: string): boolean {
  const state = useChatStore.getState()
  if ((state.summaries[conversationId]?.unreadCount ?? 0) > 0) return true
  return (state.messages[conversationId] ?? []).some(
    (message) => isIncoming(message, me()) && message.myReceipt !== 'READ',
  )
}

// "I read this conversation." Sent after a short pause, only if I am still looking at it.
export function scheduleRead(conversationId: string): void {
  const existing = readTimers.get(conversationId)
  if (existing) clearTimeout(existing)
  readTimers.set(
    conversationId,
    setTimeout(() => {
      readTimers.delete(conversationId)
      void flushRead(conversationId)
    }, ACK_DELAY_MS),
  )
}

async function flushRead(conversationId: string): Promise<void> {
  if (!isReading(conversationId) || !hasUnreadIncoming(conversationId)) return
  const incoming = (useChatStore.getState().messages[conversationId] ?? []).filter((message) =>
    isIncoming(message, me()),
  )
  const newest = incoming[incoming.length - 1]
  if (!newest) return
  try {
    await chatApi.markRead(conversationId, newest.id)
    useChatStore.getState().markConversationRead(conversationId)
  } catch {
    // Not critical: it is tried again the next time the conversation is opened.
  }
}

// ---- events from the server ----

// A group changed (members, admins, name, settings): load the conversation again.
// Several SYSTEM messages can arrive together (one change can produce a few), so they share one request.
function scheduleConversationRefresh(conversationId: string): void {
  if (refreshTimers.has(conversationId)) return
  refreshTimers.set(
    conversationId,
    setTimeout(() => {
      refreshTimers.delete(conversationId)
      refreshConversation(conversationId).catch(() => undefined)
    }, ACK_DELAY_MS),
  )
}

function onMessageCreated(message: Message): void {
  const store = useChatStore.getState()
  const conversationId = message.conversationId
  const alreadyKnown = store.messages[conversationId]?.some((existing) => existing.id === message.id) ?? false

  store.addServerMessages(conversationId, [message])

  // SYSTEM message: never acknowledged, never unread. It tells us the group changed.
  if (message.type === 'SYSTEM') {
    if (!store.conversations[conversationId]) {
      void refreshConversationList().catch(() => undefined) // e.g. I was just added to a new group
    } else if (!alreadyKnown) {
      scheduleConversationRefresh(conversationId)
    }
    return
  }

  // A chat started by somebody else: we do not know it yet.
  if (!store.conversations[conversationId]) {
    void refreshConversationList().catch(() => undefined)
  }

  if (message.senderId === me() || alreadyKnown) return // my own message, or one we already handled

  // A pop-up of the operating system, only when the tab is hidden and the user turned it on in Settings.
  showDesktopNotificationFor(message, me(), store.conversations[conversationId])

  queueDelivered(message.id)
  if (isReading(conversationId)) {
    scheduleRead(conversationId)
  } else {
    store.incrementUnread(conversationId)
  }
}

function onTyping(conversationId: string, userId: string, typing: boolean): void {
  if (userId === me()) return // the server also sends my own typing back
  setTypingWithExpiry(conversationId, userId, typing)
}

// Per person: in a group several people can type at the same time.
export function setTypingWithExpiry(conversationId: string, userId: string, typing: boolean): void {
  const key = `${conversationId}|${userId}`
  const timer = typingTimers.get(key)
  if (timer) clearTimeout(timer)
  typingTimers.delete(key)
  useChatStore.getState().setTyping(conversationId, userId, typing)
  if (typing) {
    typingTimers.set(
      key,
      setTimeout(() => {
        typingTimers.delete(key)
        useChatStore.getState().setTyping(conversationId, userId, false)
      }, TYPING_EXPIRES_MS),
    )
  }
}

export function handleRealtimeEvent(raw: unknown): void {
  if (!raw || typeof raw !== 'object' || !('type' in raw)) return
  const event = raw as RealtimeEvent
  const store = useChatStore.getState()

  switch (event.type) {
    case 'MESSAGE_CREATED':
      onMessageCreated(event.data)
      break
    case 'RECEIPT_UPDATED':
      store.receiveReceipt(event.data.conversationId, event.data.messageId, event.data.status)
      break
    case 'MESSAGE_ACK':
      store.resolvePending(event.data.message.conversationId, event.data.idempotencyKey, event.data.message)
      break
    case 'ERROR': {
      const key = event.data.idempotencyKey
      if (key) {
        for (const [conversationId, list] of Object.entries(store.messages)) {
          if (list.some((message) => message.idempotencyKey === key)) {
            store.updatePending(conversationId, key, {
              localStatus: 'FAILED',
              errorMessage: messageForSendError(event.data.code, null),
            })
          }
        }
      }
      break
    }
    case 'TYPING':
      onTyping(event.data.conversationId, event.data.userId, event.data.typing)
      break
  }
}

// ---- catching up after (re)connecting ----

let syncRunning = false

// Fetches everything that happened while we were offline and tells the server we received it.
// The server may send a message we already have (it looks back a few seconds), so everything is de-duplicated.
export async function syncAfterConnect(): Promise<void> {
  const userId = me()
  if (!userId || syncRunning) return
  syncRunning = true
  try {
    let cursor = getSyncCursor(userId)
    // The cap stops a bug (or a huge backlog) from looping forever; the next reconnect continues.
    for (let round = 0; round < 50; round++) {
      const page = await chatApi.syncMessages(cursor, 100)

      const byConversation = new Map<string, Message[]>()
      for (const message of page.items) {
        byConversation.set(message.conversationId, [...(byConversation.get(message.conversationId) ?? []), message])
        if (isIncoming(message, userId) && message.myReceipt === null) {
          queueDelivered(message.id) // received now, so tell the server (never for SYSTEM messages)
        }
      }
      byConversation.forEach((messages, conversationId) =>
        useChatStore.getState().addServerMessages(conversationId, messages),
      )

      if (page.nextCursor) {
        cursor = page.nextCursor
        saveSyncCursor(userId, cursor)
      }
      if (!page.hasMore) break
    }
  } catch {
    // Offline again or the server is busy: the next reconnect tries again.
  } finally {
    syncRunning = false
  }

  try {
    await refreshConversationList() // authoritative unread counts and last messages
  } catch {
    // keep what we have
  }
  const open = useChatStore.getState().openConversationId
  if (open && isReading(open) && hasUnreadIncoming(open)) {
    scheduleRead(open)
  }
}

// ---- start / stop ----

// Wires everything up. Returns the function that undoes it (called on logout / unmount).
export function startRealtime(connection: RealtimeConnection): () => void {
  const unsubscribers = [
    connection.subscribe('/user/queue/messages', handleRealtimeEvent),
    connection.subscribe('/user/queue/receipts', handleRealtimeEvent),
    connection.subscribe('/user/queue/acks', handleRealtimeEvent),
    connection.subscribe('/user/queue/errors', handleRealtimeEvent),
    connection.subscribe('/user/queue/typing', handleRealtimeEvent),
    connection.onConnected(() => void syncAfterConnect()),
  ]

  // Coming back to the tab: whatever arrived while it was hidden counts as read now.
  function onVisibilityChange() {
    const open = useChatStore.getState().openConversationId
    if (open && isReading(open) && hasUnreadIncoming(open)) {
      scheduleRead(open)
    }
  }
  document.addEventListener('visibilitychange', onVisibilityChange)

  return () => {
    unsubscribers.forEach((unsubscribe) => unsubscribe())
    document.removeEventListener('visibilitychange', onVisibilityChange)
    for (const timer of [...readTimers.values(), ...typingTimers.values(), ...refreshTimers.values()]) clearTimeout(timer)
    readTimers.clear()
    typingTimers.clear()
    refreshTimers.clear()
    if (deliveredTimer) clearTimeout(deliveredTimer)
    deliveredTimer = null
    pendingDelivered.clear()
  }
}
