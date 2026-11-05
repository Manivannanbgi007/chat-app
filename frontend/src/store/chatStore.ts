import { create } from 'zustand'
import {
  applyReceipt,
  markIncomingRead,
  mergeMessages,
  replacePending,
  toMicros,
} from '../features/messaging/messageMerge'
import type { SummaryState } from '../features/messaging/conversationList'
import type { ChatMessage, Conversation, ConversationSummary, Message, ReceiptStatus } from '../types/chat'
import { useAuthStore } from './authStore'

// All chat data in memory: conversations, messages, unread counts, typing.
// The functions that change the data are small and synchronous; the code that talks to the server
// lives in features/messaging (chatActions.ts and realtime.ts).

export interface PagingState {
  nextCursor: string | null // cursor of the next OLDER page
  hasMore: boolean
  loaded: boolean // the newest page was fetched
  loadingOlder: boolean
}

interface ChatState {
  conversationsLoaded: boolean
  conversations: Record<string, Conversation>
  summaries: Record<string, SummaryState>
  messages: Record<string, ChatMessage[]>
  paging: Record<string, PagingState>
  // conversationId -> ids of the people typing there right now (a group can have several)
  typing: Record<string, string[]>
  openConversationId: string | null

  reset: () => void
  setOpenConversation: (id: string | null) => void
  setConversationList: (conversations: Conversation[], summaries: ConversationSummary[]) => void
  upsertConversation: (conversation: Conversation) => void
  addServerMessages: (conversationId: string, messages: Message[]) => void
  addPending: (message: ChatMessage) => void
  resolvePending: (conversationId: string, idempotencyKey: string, serverMessage: Message) => void
  updatePending: (conversationId: string, idempotencyKey: string, changes: Partial<ChatMessage>) => void
  removePending: (conversationId: string, idempotencyKey: string) => void
  receiveReceipt: (conversationId: string, messageId: string, status: ReceiptStatus) => void
  setPaging: (conversationId: string, changes: Partial<PagingState>) => void
  setUnread: (conversationId: string, count: number) => void
  incrementUnread: (conversationId: string) => void
  markConversationRead: (conversationId: string) => void
  setTyping: (conversationId: string, userId: string, typing: boolean) => void
}

const emptyPaging: PagingState = { nextCursor: null, hasMore: false, loaded: false, loadingOlder: false }

const initialState = {
  conversationsLoaded: false,
  conversations: {} as Record<string, Conversation>,
  summaries: {} as Record<string, SummaryState>,
  messages: {} as Record<string, ChatMessage[]>,
  paging: {} as Record<string, PagingState>,
  typing: {} as Record<string, string[]>,
  openConversationId: null as string | null,
}

function currentUserId(): string {
  return useAuthStore.getState().user?.id ?? ''
}

// The newer of two messages (by time). Used for the "last message" preview in the chat list.
function newer(a: ChatMessage | null, b: ChatMessage | null): ChatMessage | null {
  if (!a) return b
  if (!b) return a
  return toMicros(b.createdAt) >= toMicros(a.createdAt) ? b : a
}

export const useChatStore = create<ChatState>((set) => ({
  ...initialState,

  // Called on logout so the next user never sees someone else's chats.
  reset: () => set({ ...initialState }),

  setOpenConversation: (id) => set({ openConversationId: id }),

  setConversationList: (conversations, summaries) =>
    set((state) => {
      const nextSummaries: Record<string, SummaryState> = { ...state.summaries }
      for (const summary of summaries) {
        const previous = nextSummaries[summary.conversationId]
        nextSummaries[summary.conversationId] = {
          unreadCount: summary.unreadCount,
          // keep a newer pending message the server does not know yet
          lastMessage: newer(summary.lastMessage, previous?.lastMessage ?? null),
        }
      }
      return {
        conversationsLoaded: true,
        conversations: Object.fromEntries(conversations.map((conversation) => [conversation.id, conversation])),
        summaries: nextSummaries,
      }
    }),

  upsertConversation: (conversation) =>
    set((state) => ({ conversations: { ...state.conversations, [conversation.id]: conversation } })),

  // Messages from the server (history page, live event, sync). Duplicates are removed.
  addServerMessages: (conversationId, incoming) =>
    set((state) => {
      if (incoming.length === 0) return state
      const messages = mergeMessages(state.messages[conversationId] ?? [], incoming, currentUserId())
      const previousSummary = state.summaries[conversationId] ?? { unreadCount: 0, lastMessage: null }
      const newest = incoming.reduce<ChatMessage | null>((best, message) => newer(best, message), previousSummary.lastMessage)
      return {
        messages: { ...state.messages, [conversationId]: messages },
        summaries: { ...state.summaries, [conversationId]: { ...previousSummary, lastMessage: newest } },
      }
    }),

  // A message I just typed, shown at once while the request is still running.
  addPending: (message) =>
    set((state) => {
      const list = [...(state.messages[message.conversationId] ?? []), message]
      const previousSummary = state.summaries[message.conversationId] ?? { unreadCount: 0, lastMessage: null }
      return {
        messages: { ...state.messages, [message.conversationId]: list },
        summaries: {
          ...state.summaries,
          [message.conversationId]: { ...previousSummary, lastMessage: newer(previousSummary.lastMessage, message) },
        },
      }
    }),

  // The server accepted my message: swap the pending copy for the real one.
  resolvePending: (conversationId, idempotencyKey, serverMessage) =>
    set((state) => {
      const messages = replacePending(state.messages[conversationId] ?? [], idempotencyKey, serverMessage, currentUserId())
      const previousSummary = state.summaries[conversationId] ?? { unreadCount: 0, lastMessage: null }
      const wasPreview = previousSummary.lastMessage?.idempotencyKey === idempotencyKey
      return {
        messages: { ...state.messages, [conversationId]: messages },
        summaries: {
          ...state.summaries,
          [conversationId]: {
            ...previousSummary,
            lastMessage: wasPreview
              ? { ...serverMessage }
              : newer(previousSummary.lastMessage, serverMessage),
          },
        },
      }
    }),

  // Changes a pending message (SENDING <-> FAILED).
  updatePending: (conversationId, idempotencyKey, changes) =>
    set((state) => ({
      messages: {
        ...state.messages,
        [conversationId]: (state.messages[conversationId] ?? []).map((message) =>
          message.idempotencyKey === idempotencyKey && message.localStatus ? { ...message, ...changes } : message,
        ),
      },
    })),

  // A message that was never sent disappears (the user cancelled it, or the server rejected the file).
  removePending: (conversationId, idempotencyKey) =>
    set((state) => {
      const list = (state.messages[conversationId] ?? []).filter(
        (message) => !(message.idempotencyKey === idempotencyKey && message.localStatus),
      )
      const summary = state.summaries[conversationId]
      const lastWasThis = summary?.lastMessage?.idempotencyKey === idempotencyKey
      return {
        messages: { ...state.messages, [conversationId]: list },
        summaries:
          summary && lastWasThis
            ? { ...state.summaries, [conversationId]: { ...summary, lastMessage: list[list.length - 1] ?? null } }
            : state.summaries,
      }
    }),

  // Someone delivered/read a message I sent. The status never moves backwards.
  receiveReceipt: (conversationId, messageId, status) =>
    set((state) => {
      const list = state.messages[conversationId]
      const summary = state.summaries[conversationId]
      const nextList = list ? applyReceipt(list, messageId, status) : list
      const nextLast =
        summary?.lastMessage && summary.lastMessage.id === messageId
          ? (applyReceipt([summary.lastMessage], messageId, status)[0] ?? summary.lastMessage)
          : summary?.lastMessage
      return {
        messages: list ? { ...state.messages, [conversationId]: nextList } : state.messages,
        summaries: summary ? { ...state.summaries, [conversationId]: { ...summary, lastMessage: nextLast ?? null } } : state.summaries,
      }
    }),

  setPaging: (conversationId, changes) =>
    set((state) => ({
      paging: { ...state.paging, [conversationId]: { ...(state.paging[conversationId] ?? emptyPaging), ...changes } },
    })),

  setUnread: (conversationId, count) =>
    set((state) => ({
      summaries: {
        ...state.summaries,
        [conversationId]: { ...(state.summaries[conversationId] ?? { lastMessage: null }), unreadCount: count },
      },
    })),

  incrementUnread: (conversationId) =>
    set((state) => {
      const previous = state.summaries[conversationId] ?? { unreadCount: 0, lastMessage: null }
      return { summaries: { ...state.summaries, [conversationId]: { ...previous, unreadCount: previous.unreadCount + 1 } } }
    }),

  // I read the conversation: no unread messages any more.
  markConversationRead: (conversationId) =>
    set((state) => {
      const previous = state.summaries[conversationId] ?? { unreadCount: 0, lastMessage: null }
      const list = state.messages[conversationId]
      return {
        summaries: { ...state.summaries, [conversationId]: { ...previous, unreadCount: 0 } },
        messages: list
          ? { ...state.messages, [conversationId]: markIncomingRead(list, currentUserId()) }
          : state.messages,
      }
    }),

  // One person started or stopped typing in a conversation.
  setTyping: (conversationId, userId, typing) =>
    set((state) => {
      const current = state.typing[conversationId] ?? []
      const isTyping = current.includes(userId)
      if (isTyping === typing) return state
      const next = typing ? [...current, userId] : current.filter((id) => id !== userId)
      return { typing: { ...state.typing, [conversationId]: next } }
    }),
}))
