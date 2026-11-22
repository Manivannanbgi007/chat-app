import { apiClient } from './apiClient'

// conversation-service and message-service calls. Each returns the "data" part of the envelope
// (see types/chat.js for the shapes of conversations, messages and pages).

// ---- conversations ----

// Starts a one-to-one chat (or returns the existing one with that person).
export async function createConversation(userId) {
  const response = await apiClient.post('/api/conversations', { type: 'PRIVATE', userId })
  return response.data.data
}

export async function getConversations() {
  const response = await apiClient.get('/api/conversations')
  return response.data.data
}

export async function getConversation(id) {
  const response = await apiClient.get(`/api/conversations/${id}`)
  return response.data.data
}

// ---- groups (every call answers with the conversation as I see it now) ----

// body: { name, description?, memberIds } - memberIds are the other people; I become the admin.
export async function createGroup(body) {
  const response = await apiClient.post('/api/conversations', { type: 'GROUP', ...body })
  return response.data.data
}

// body: { name?, description?, onlyAdminsCanSend?, onlyAdminsCanEdit? }
// Only the fields that are sent are changed. description "" removes the description.
export async function updateGroup(conversationId, body) {
  const response = await apiClient.patch(`/api/conversations/${conversationId}/group`, body)
  return response.data.data
}

export async function addMembers(conversationId, userIds) {
  const response = await apiClient.post(`/api/conversations/${conversationId}/members`, {
    userIds,
  })
  return response.data.data
}

export async function removeMember(conversationId, userId) {
  const response = await apiClient.delete(`/api/conversations/${conversationId}/members/${userId}`)
  return response.data.data
}

// role: 'MEMBER' | 'ADMIN'
export async function setMemberRole(conversationId, userId, role) {
  const response = await apiClient.put(`/api/conversations/${conversationId}/members/${userId}/role`, { role })
  return response.data.data
}

export async function leaveGroup(conversationId) {
  const response = await apiClient.post(`/api/conversations/${conversationId}/leave`)
  return response.data.data
}

// ---- my chat settings: mute, archive, pin ----

// duration: 'EIGHT_HOURS' | 'ONE_WEEK' | 'ALWAYS'
export async function muteConversation(conversationId, duration) {
  const response = await apiClient.put(`/api/conversations/${conversationId}/mute`, {
    duration,
  })
  return response.data.data
}

export async function unmuteConversation(conversationId) {
  const response = await apiClient.delete(`/api/conversations/${conversationId}/mute`)
  return response.data.data
}

export async function archiveConversation(conversationId) {
  const response = await apiClient.put(`/api/conversations/${conversationId}/archive`)
  return response.data.data
}

export async function unarchiveConversation(conversationId) {
  const response = await apiClient.delete(`/api/conversations/${conversationId}/archive`)
  return response.data.data
}

export async function pinConversation(conversationId) {
  const response = await apiClient.put(`/api/conversations/${conversationId}/pin`)
  return response.data.data
}

export async function unpinConversation(conversationId) {
  const response = await apiClient.delete(`/api/conversations/${conversationId}/pin`)
  return response.data.data
}

// ---- messages ----

// body: { conversationId, content?, type?, attachmentId?, idempotencyKey }
//   content:        text messages: the text. Media messages: the caption (may be empty or left out).
//   type:           TEXT unless the message carries a file.
//   attachmentId:   media messages: the id returned by the upload.
//   idempotencyKey: chosen by the client. Sending the same key again returns the same message instead of a new one.
export async function sendMessage(body) {
  const response = await apiClient.post('/api/messages', { type: 'TEXT', ...body })
  return response.data.data
}

// A fresh signed link to a message's file. variant "thumbnail" = the small preview of an image.
export async function getAttachmentUrl(messageId, variant = 'original') {
  const response = await apiClient.get(`/api/messages/${messageId}/attachment/url`, {
    params: { variant },
  })
  return response.data.data
}

// params: { before?, after?, limit? }. before = older page (newest first). after = newer messages (oldest first).
export async function getMessages(conversationId, params = {}) {
  const response = await apiClient.get(`/api/conversations/${conversationId}/messages`, { params })
  return response.data.data
}

// "My device received these messages."
export async function markDelivered(messageIds) {
  await apiClient.post('/api/messages/delivered', { messageIds })
}

// "I read everything in this conversation up to this message."
export async function markRead(conversationId, upToMessageId) {
  await apiClient.post(`/api/conversations/${conversationId}/read`, { upToMessageId })
}

export async function getSummaries() {
  const response = await apiClient.get('/api/messages/summaries')
  return response.data.data
}

// Everything new across all my conversations since the cursor (oldest first).
// Without a cursor the answer is empty and nextCursor is "now": start from there.
export async function syncMessages(after, limit = 100) {
  const response = await apiClient.get('/api/messages/sync', { params: { after, limit } })
  return response.data.data
}

// Who received / read a message I sent (only the sender may ask).
export async function getMessageReceipts(messageId) {
  const response = await apiClient.get(`/api/messages/${messageId}/receipts`)
  return response.data.data
}
