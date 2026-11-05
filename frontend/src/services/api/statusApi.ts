import type { ApiResponse } from '../../types/api'
import type { SignedUrl } from '../../types/chat'
import type {
  CreateStatusBody,
  MyStatus,
  Status,
  StatusGroup,
  StatusPrivacy,
  StatusViewers,
} from '../../types/status'
import { apiClient } from './apiClient'

// status-service calls (API_DOCUMENTATION.md section 7.4). Each returns the "data" part of the envelope.

// Post a text, photo or video status. Sending the same idempotencyKey again returns the status that was
// already saved (so a retry after a lost answer never posts twice).
export async function createStatus(body: CreateStatusBody): Promise<Status> {
  const response = await apiClient.post<ApiResponse<Status>>('/api/statuses', body)
  return response.data.data
}

// My statuses that have not expired yet, oldest first, each with its view count.
export async function getMyStatuses(): Promise<MyStatus[]> {
  const response = await apiClient.get<ApiResponse<MyStatus[]>>('/api/statuses/mine')
  return response.data.data
}

// Everybody whose statuses I may see, grouped per person (people with something new first).
export async function getStatusFeed(): Promise<StatusGroup[]> {
  const response = await apiClient.get<ApiResponse<StatusGroup[]>>('/api/statuses/feed')
  return response.data.data
}

// "I saw this status." Safe to send twice.
export async function markStatusViewed(id: string): Promise<void> {
  await apiClient.post(`/api/statuses/${id}/view`)
}

// Who saw one of MY statuses.
export async function getStatusViewers(id: string): Promise<StatusViewers> {
  const response = await apiClient.get<ApiResponse<StatusViewers>>(`/api/statuses/${id}/viewers`)
  return response.data.data
}

// A signed link (valid 5 minutes) to the photo / video of a status, or to the photo's small preview.
export async function getStatusMediaUrl(id: string, variant: 'original' | 'thumbnail' = 'original'): Promise<SignedUrl> {
  const response = await apiClient.get<ApiResponse<SignedUrl>>(`/api/statuses/${id}/media-url`, { params: { variant } })
  return response.data.data
}

// Delete one of my statuses (its photo / video is deleted too).
export async function deleteStatus(id: string): Promise<void> {
  await apiClient.delete(`/api/statuses/${id}`)
}

export async function getStatusPrivacy(): Promise<StatusPrivacy> {
  const response = await apiClient.get<ApiResponse<StatusPrivacy>>('/api/statuses/privacy')
  return response.data.data
}

export async function updateStatusPrivacy(body: StatusPrivacy): Promise<StatusPrivacy> {
  const response = await apiClient.put<ApiResponse<StatusPrivacy>>('/api/statuses/privacy', body)
  return response.data.data
}
