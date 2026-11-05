// Things the chat does that need the server: load lists and history, send messages.
// They read and write the chat store (store/chatStore.ts).
import * as chatApi from '../../services/api/chatApi'
import { toAppError, type AppError } from '../../services/api/appError'
import { useAuthStore } from '../../store/authStore'
import { useChatStore } from '../../store/chatStore'
import type { ChatMessage, Conversation, MemberRole, MuteDuration } from '../../types/chat'

const PAGE_SIZE = 30

// Loads the chat list and the unread counts / last messages together.
export async function refreshConversationList(): Promise<void> {
  const [conversations, summaries] = await Promise.all([chatApi.getConversations(), chatApi.getSummaries()])
  useChatStore.getState().setConversationList(conversations, summaries)
}

// Makes sure we know the conversation (for example when the page is opened directly with its URL).
// Throws an AppError (404) if it does not exist or is not mine.
export async function ensureConversation(conversationId: string): Promise<void> {
  if (useChatStore.getState().conversations[conversationId]) return
  const conversation = await chatApi.getConversation(conversationId)
  useChatStore.getState().upsertConversation(conversation)
}

// Loads one conversation again (members, roles, name or my settings changed) and stores it.
export async function refreshConversation(conversationId: string): Promise<void> {
  const conversation = await chatApi.getConversation(conversationId)
  useChatStore.getState().upsertConversation(conversation)
}

// Starts (or finds) the chat with a person and returns its id.
export async function startConversation(userId: string): Promise<string> {
  const conversation = await chatApi.createConversation(userId)
  useChatStore.getState().upsertConversation(conversation)
  return conversation.id
}

// The newest page of messages. Messages we already have are not duplicated.
export async function loadLatestMessages(conversationId: string): Promise<void> {
  const store = useChatStore.getState()
  const page = await chatApi.getMessages(conversationId, { limit: PAGE_SIZE })
  store.addServerMessages(conversationId, page.items)
  // Only the first load decides where "older" starts; later loads just merge.
  if (!useChatStore.getState().paging[conversationId]?.loaded) {
    store.setPaging(conversationId, { loaded: true, nextCursor: page.nextCursor, hasMore: page.hasMore })
  }
}

// One page older than what is on screen (called when the user scrolls to the top).
export async function loadOlderMessages(conversationId: string): Promise<void> {
  const store = useChatStore.getState()
  const paging = store.paging[conversationId]
  if (!paging || !paging.hasMore || paging.loadingOlder || !paging.nextCursor) return

  store.setPaging(conversationId, { loadingOlder: true })
  try {
    const page = await chatApi.getMessages(conversationId, { before: paging.nextCursor, limit: PAGE_SIZE })
    store.addServerMessages(conversationId, page.items)
    store.setPaging(conversationId, { nextCursor: page.nextCursor, hasMore: page.hasMore, loadingOlder: false })
  } catch (error) {
    store.setPaging(conversationId, { loadingOlder: false })
    throw error
  }
}

// ---- sending ----

// Shows the message at once (status SENDING), then sends it.
// The idempotency key is created here ONCE: a retry sends the same key, so the server
// returns the already saved message instead of creating a second one.
export async function sendMessage(conversationId: string, text: string): Promise<void> {
  const user = useAuthStore.getState().user
  const content = text.trim()
  if (!user || !content) return

  const key = crypto.randomUUID()
  const pending: ChatMessage = {
    id: `local-${key}`,
    conversationId,
    senderId: user.id,
    type: 'TEXT',
    content,
    createdAt: new Date().toISOString(),
    status: null,
    myReceipt: null,
    localStatus: 'SENDING',
    idempotencyKey: key,
  }
  useChatStore.getState().addPending(pending)
  await postPending(pending)
}

// The "Retry" button of a failed message.
export async function retryMessage(conversationId: string, idempotencyKey: string): Promise<void> {
  const store = useChatStore.getState()
  const failed = store.messages[conversationId]?.find(
    (message) => message.idempotencyKey === idempotencyKey && message.localStatus === 'FAILED',
  )
  if (!failed) return
  store.updatePending(conversationId, idempotencyKey, { localStatus: 'SENDING', errorMessage: undefined })
  await postPending(failed)
}

async function postPending(pending: ChatMessage): Promise<void> {
  const store = useChatStore.getState()
  try {
    const saved = await chatApi.sendMessage({
      conversationId: pending.conversationId,
      content: pending.content,
      idempotencyKey: pending.idempotencyKey!,
    })
    store.resolvePending(pending.conversationId, pending.idempotencyKey!, saved)
  } catch (error) {
    store.updatePending(pending.conversationId, pending.idempotencyKey!, {
      localStatus: 'FAILED',
      errorMessage: describeSendError(error),
    })
    // Not allowed any more (removed from the group, "only admins" switched on): reload the chat so the
    // composer is replaced by the right explanation.
    const appError = toAppError(error)
    if (appError.kind === 'NOT_FOUND' || appError.errorCode === 'ONLY_ADMINS_CAN_SEND') {
      refreshConversation(pending.conversationId).catch(() => undefined)
    }
  }
}

export function describeSendError(error: unknown): string {
  const appError = toAppError(error)
  if (appError.kind === 'VALIDATION') return appError.message
  return messageForSendError(appError.errorCode, appError.kind)
}

// A friendly sentence for a failed send. `code` is the backend errorCode (or the code of a STOMP ERROR frame).
export function messageForSendError(code: string | null, kind: string | null): string {
  if (code === 'MESSAGING_NOT_ALLOWED') return "You can't message this user."
  if (code === 'ONLY_ADMINS_CAN_SEND') return 'Only admins can send messages to this group.'
  // 404: the chat is gone, or (in a group) I am no longer a participant.
  if (code === 'NOT_FOUND' || kind === 'NOT_FOUND') return "You can't send messages to this chat any more."
  if (kind === 'NETWORK') return 'No connection. Press Retry when you are back online.'
  if (code === 'RATE_LIMITED' || kind === 'RATE_LIMITED') return 'You are sending too fast. Wait a moment and press Retry.'
  return 'The message could not be sent. Press Retry.'
}

// ---- groups ----

// Every group / settings call answers with the conversation as I see it now: store it, so all screens update.
async function applyChange(conversationId: string, call: () => Promise<Conversation>): Promise<Conversation> {
  try {
    const conversation = await call()
    useChatStore.getState().upsertConversation(conversation)
    return conversation
  } catch (error) {
    const appError = toAppError(error)
    // My view was out of date (I was removed, or lost admin rights): load the real state.
    if (appError.errorCode === 'NOT_GROUP_ADMIN' || appError.errorCode === 'NOT_A_MEMBER') {
      refreshConversation(conversationId).catch(() => undefined)
    }
    throw appError
  }
}

// Creates a group with me as admin and returns its id.
export async function createGroup(name: string, description: string, memberIds: string[]): Promise<string> {
  const trimmedDescription = description.trim()
  const conversation = await chatApi.createGroup({
    name: name.trim(),
    ...(trimmedDescription ? { description: trimmedDescription } : {}),
    memberIds,
  })
  useChatStore.getState().upsertConversation(conversation)
  return conversation.id
}

export function updateGroupInfo(conversationId: string, changes: chatApi.UpdateGroupBody): Promise<Conversation> {
  return applyChange(conversationId, () => chatApi.updateGroup(conversationId, changes))
}

export function addGroupMembers(conversationId: string, userIds: string[]): Promise<Conversation> {
  return applyChange(conversationId, () => chatApi.addMembers(conversationId, userIds))
}

export function removeGroupMember(conversationId: string, userId: string): Promise<Conversation> {
  return applyChange(conversationId, () => chatApi.removeMember(conversationId, userId))
}

export function changeMemberRole(conversationId: string, userId: string, role: MemberRole): Promise<Conversation> {
  return applyChange(conversationId, () => chatApi.setMemberRole(conversationId, userId, role))
}

export function leaveGroup(conversationId: string): Promise<Conversation> {
  return applyChange(conversationId, () => chatApi.leaveGroup(conversationId))
}

// ---- my chat settings ----

// duration null = unmute.
export function setMuted(conversationId: string, duration: MuteDuration | null): Promise<Conversation> {
  return applyChange(conversationId, () =>
    duration ? chatApi.muteConversation(conversationId, duration) : chatApi.unmuteConversation(conversationId),
  )
}

export function setArchived(conversationId: string, archived: boolean): Promise<Conversation> {
  return applyChange(conversationId, () =>
    archived ? chatApi.archiveConversation(conversationId) : chatApi.unarchiveConversation(conversationId),
  )
}

export function setPinned(conversationId: string, pinned: boolean): Promise<Conversation> {
  return applyChange(conversationId, () =>
    pinned ? chatApi.pinConversation(conversationId) : chatApi.unpinConversation(conversationId),
  )
}

// A friendly sentence for a failed group or chat-settings call.
export function describeGroupError(error: unknown): string {
  const appError: AppError = toAppError(error)
  switch (appError.errorCode) {
    case 'GROUP_ADD_NOT_ALLOWED':
      return "Some people can't be added because of their privacy settings. Remove them and try again."
    case 'GROUP_FULL':
      return 'This group is full: a group can have at most 256 members.'
    case 'NOT_GROUP_ADMIN':
      return 'Only group admins can do that.'
    case 'NOT_A_MEMBER':
      return "You're no longer a participant of this group."
    case 'ALREADY_MEMBER':
      return 'They are already in the group.'
    case 'CANNOT_CHANGE_SELF':
      return 'You cannot do that to yourself. Use "Exit group" to leave.'
    case 'PIN_LIMIT_REACHED':
      return 'You can only pin up to 3 chats.'
    case 'NOT_A_GROUP':
      return 'This is not a group.'
    default:
      return appError.message
  }
}

// The people a GROUP_ADD_NOT_ALLOWED answer names (empty for any other error).
export function refusedUserIds(error: unknown): string[] {
  const appError = toAppError(error)
  if (appError.errorCode !== 'GROUP_ADD_NOT_ALLOWED') return []
  const ids = appError.details.userIds
  return Array.isArray(ids) ? ids.filter((id): id is string => typeof id === 'string') : []
}
