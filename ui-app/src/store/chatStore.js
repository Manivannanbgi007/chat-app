import { create } from 'zustand'
import {
  applyReceipt,
  markIncomingRead,
  mergeMessages,
  replacePending,
  toMicros,
} from '../features/messaging/messageMerge'
import { useAuthStore } from './authStore'

// All chat data in memory: conversations, messages, unread counts, typing.
// The functions that change the data are small and synchronous; the code that talks to the server
// lives in features/messaging (chatActions.js and realtime.js).
//
// State:
//   conversationsLoaded  the chat list was fetched at least once
//   conversations        { [conversationId]: Conversation }
//   summaries            { [conversationId]: { unreadCount, lastMessage } }
//   messages             { [conversationId]: ChatMessage[] } (oldest first)
//   paging               { [conversationId]: { nextCursor, hasMore, loaded, loadingOlder } }
//                          nextCursor = cursor of the next OLDER page, loaded = the newest page was fetched
//   typing               { [conversationId]: userId[] } the people typing there right now (a group can have several)
//   openConversationId   the chat on screen (null = none)

const emptyPaging = { nextCursor: null, hasMore: false, loaded: false, loadingOlder: false }

const initialState = {
  conversationsLoaded: false,
  conversations: {},
  summaries: {},
  messages: {},
  paging: {},
  typing: {},
  openConversationId: null,
}

function currentUserId() {
  return useAuthStore.getState().user?.id ?? ''
}

// The newer of two messages (by time). Used for the "last message" preview in the chat list.
function newer(a, b) {
  if (!a) return b
  if (!b) return a
  return toMicros(b.createdAt) >= toMicros(a.createdAt) ? b : a
}

export const useChatStore = create((set) => ({
  ...initialState,

  // Called on logout so the next user never sees someone else's chats.
  reset: () => set({ ...initialState }),

  setOpenConversation: (id) => set({ openConversationId: id }),

  setConversationList: (conversations, summaries) =>
    set((state) => {
      const nextSummaries = { ...state.summaries }
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
      const newest = incoming.reduce((best, message) => newer(best, message), previousSummary.lastMessage)
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
            lastMessage: wasPreview ? { ...serverMessage } : newer(previousSummary.lastMessage, serverMessage),
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
        messages: list ? { ...state.messages, [conversationId]: markIncomingRead(list, currentUserId()) } : state.messages,
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
