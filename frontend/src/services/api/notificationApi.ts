import type { ApiResponse } from '../../types/api'
import type { AppNotification, NotificationPage, NotificationPreferences } from '../../types/notification'
import { apiClient } from './apiClient'

// notification-service calls (API_DOCUMENTATION.md section 7.2). Each returns the "data" part of the envelope.

export async function getNotifications(page: number, size = 20): Promise<NotificationPage> {
  const response = await apiClient.get<ApiResponse<NotificationPage>>('/api/notifications', { params: { page, size } })
  return response.data.data
}

export async function getUnreadCount(): Promise<number> {
  const response = await apiClient.get<ApiResponse<{ unreadCount: number }>>('/api/notifications/unread-count')
  return response.data.data.unreadCount
}

export async function markNotificationRead(id: string): Promise<AppNotification> {
  const response = await apiClient.put<ApiResponse<AppNotification>>(`/api/notifications/${id}/read`)
  return response.data.data
}

export async function markAllNotificationsRead(): Promise<number> {
  const response = await apiClient.put<ApiResponse<{ updated: number }>>('/api/notifications/read-all')
  return response.data.data.updated
}

export async function getNotificationPreferences(): Promise<NotificationPreferences> {
  const response = await apiClient.get<ApiResponse<NotificationPreferences>>('/api/notifications/preferences')
  return response.data.data
}

export async function updateNotificationPreferences(body: NotificationPreferences): Promise<NotificationPreferences> {
  const response = await apiClient.put<ApiResponse<NotificationPreferences>>('/api/notifications/preferences', body)
  return response.data.data
}
