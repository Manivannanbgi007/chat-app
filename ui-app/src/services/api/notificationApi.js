import { apiClient } from './apiClient'

// notification-service calls (API_DOCUMENTATION.md section 7.2). Each returns the "data" part of the envelope
// (see types/notification.js for the shapes).

export async function getNotifications(page, size = 20) {
  const response = await apiClient.get('/api/notifications', { params: { page, size } })
  return response.data.data
}

export async function getUnreadCount() {
  const response = await apiClient.get('/api/notifications/unread-count')
  return response.data.data.unreadCount
}

export async function markNotificationRead(id) {
  const response = await apiClient.put(`/api/notifications/${id}/read`)
  return response.data.data
}

export async function markAllNotificationsRead() {
  const response = await apiClient.put('/api/notifications/read-all')
  return response.data.data.updated
}

export async function getNotificationPreferences() {
  const response = await apiClient.get('/api/notifications/preferences')
  return response.data.data
}

// body: { pushEnabled, messagesEnabled, groupsEnabled, callsEnabled }
export async function updateNotificationPreferences(body) {
  const response = await apiClient.put('/api/notifications/preferences', body)
  return response.data.data
}
