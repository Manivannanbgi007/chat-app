import type { ApiResponse } from '../../types/api'
import type {
  Conversation,
  ConversationSummary,
  MemberRole,
  Message,
  MessagePage,
  MessageReceiptInfo,
  MessageType,
  MuteDuration,
  SignedUrl,
} from '../../types/chat'
import { apiClient } from './apiClient'

// conversation-service and message-service calls. Each returns the "data" part of the envelope.

// ---- conversations ----

// Starts a one-to-one chat (or returns the existing one with that person).
export async function createConversation(userId: string): Promise<Conversation> {
  const response = await apiClient.post<ApiResponse<Conversation>>('/api/conversations', { type: 'PRIVATE', userId })
  return response.data.data
}

export async function getConversations(): Promise<Conversation[]> {
  const response = await apiClient.get<ApiResponse<Conversation[]>>('/api/conversations')
  return response.data.data
}

export async function getConversation(id: string): Promise<Conversation> {
  const response = await apiClient.get<ApiResponse<Conversation>>(`/api/conversations/${id}`)
  return response.data.data
}

// ---- groups (every call answers with the conversation as I see it now) ----

export interface CreateGroupBody {
  name: string
  description?: string
  memberIds: string[] // the other people; I become the admin
}

export async function createGroup(body: CreateGroupBody): Promise<Conversation> {
  const response = await apiClient.post<ApiResponse<Conversation>>('/api/conversations', { type: 'GROUP', ...body })
  return response.data.data
}

// Only the fields that are sent are changed. description "" removes the description.
export interface UpdateGroupBody {
  name?: string
  description?: string
  onlyAdminsCanSend?: boolean
  onlyAdminsCanEdit?: boolean
}

export async function updateGroup(conversationId: string, body: UpdateGroupBody): Promise<Conversation> {
  const response = await apiClient.patch<ApiResponse<Conversation>>(`/api/conversations/${conversationId}/group`, body)
  return response.data.data
}

export async function addMembers(conversationId: string, userIds: string[]): Promise<Conversation> {
  const response = await apiClient.post<ApiResponse<Conversation>>(`/api/conversations/${conversationId}/members`, {
    userIds,
  })
  return response.data.data
}

export async function removeMember(conversationId: string, userId: string): Promise<Conversation> {
  const response = await apiClient.delete<ApiResponse<Conversation>>(
    `/api/conversations/${conversationId}/members/${userId}`,
  )
  return response.data.data
}

export async function setMemberRole(conversationId: string, userId: string, role: MemberRole): Promise<Conversation> {
  const response = await apiClient.put<ApiResponse<Conversation>>(
    `/api/conversations/${conversationId}/members/${userId}/role`,
    { role },
  )
  return response.data.data
}

export async function leaveGroup(conversationId: string): Promise<Conversation> {
  const response = await apiClient.post<ApiResponse<Conversation>>(`/api/conversations/${conversationId}/leave`)
  return response.data.data
}

// ---- my chat settings: mute, archive, pin ----

export async function muteConversation(conversationId: string, duration: MuteDuration): Promise<Conversation> {
  const response = await apiClient.put<ApiResponse<Conversation>>(`/api/conversations/${conversationId}/mute`, {
    duration,
  })
  return response.data.data
}

export async function unmuteConversation(conversationId: string): Promise<Conversation> {
  const response = await apiClient.delete<ApiResponse<Conversation>>(`/api/conversations/${conversationId}/mute`)
  return response.data.data
}

export async function archiveConversation(conversationId: string): Promise<Conversation> {
  const response = await apiClient.put<ApiResponse<Conversation>>(`/api/conversations/${conversationId}/archive`)
  return response.data.data
}

export async function unarchiveConversation(conversationId: string): Promise<Conversation> {
  const response = await apiClient.delete<ApiResponse<Conversation>>(`/api/conversations/${conversationId}/archive`)
  return response.data.data
}

export async function pinConversation(conversationId: string): Promise<Conversation> {
  const response = await apiClient.put<ApiResponse<Conversation>>(`/api/conversations/${conversationId}/pin`)
  return response.data.data
}

export async function unpinConversation(conversationId: string): Promise<Conversation> {
  const response = await apiClient.delete<ApiResponse<Conversation>>(`/api/conversations/${conversationId}/pin`)
  return response.data.data
}

// ---- messages ----

export interface SendMessageBody {
  conversationId: string
  // Text messages: the text. Media messages: the caption (may be empty or left out).
  content?: string
  // TEXT unless the message carries a file.
  type?: MessageType
  // Media messages: the id returned by the upload.
  attachmentId?: string
  // Chosen by the client. Sending the same key again returns the same message instead of a new one.
  idempotencyKey: string
}

export async function sendMessage(body: SendMessageBody): Promise<Message> {
  const response = await apiClient.post<ApiResponse<Message>>('/api/messages', { type: 'TEXT', ...body })
  return response.data.data
}

// A fresh signed link to a message's file. variant "thumbnail" = the small preview of an image.
export async function getAttachmentUrl(messageId: string, variant: 'original' | 'thumbnail' = 'original'): Promise<SignedUrl> {
  const response = await apiClient.get<ApiResponse<SignedUrl>>(`/api/messages/${messageId}/attachment/url`, {
    params: { variant },
  })
  return response.data.data
}

// before = older page (newest first). after = newer messages (oldest first).
export async function getMessages(
  conversationId: string,
  params: { before?: string; after?: string; limit?: number } = {},
): Promise<MessagePage> {
  const response = await apiClient.get<ApiResponse<MessagePage>>(`/api/conversations/${conversationId}/messages`, { params })
  return response.data.data
}

// "My device received these messages."
export async function markDelivered(messageIds: string[]): Promise<void> {
  await apiClient.post('/api/messages/delivered', { messageIds })
}

// "I read everything in this conversation up to this message."
export async function markRead(conversationId: string, upToMessageId: string): Promise<void> {
  await apiClient.post(`/api/conversations/${conversationId}/read`, { upToMessageId })
}

export async function getSummaries(): Promise<ConversationSummary[]> {
  const response = await apiClient.get<ApiResponse<ConversationSummary[]>>('/api/messages/summaries')
  return response.data.data
}

// Everything new across all my conversations since the cursor (oldest first).
// Without a cursor the answer is empty and nextCursor is "now": start from there.
export async function syncMessages(after?: string, limit = 100): Promise<MessagePage> {
  const response = await apiClient.get<ApiResponse<MessagePage>>('/api/messages/sync', { params: { after, limit } })
  return response.data.data
}

// Who received / read a message I sent (only the sender may ask).
export async function getMessageReceipts(messageId: string): Promise<MessageReceiptInfo[]> {
  const response = await apiClient.get<ApiResponse<MessageReceiptInfo[]>>(`/api/messages/${messageId}/receipts`)
  return response.data.data
}
