import { apiClient } from './apiClient'

// status-service calls (API_DOCUMENTATION.md section 7.4). Each returns the "data" part of the envelope
// (see types/status.js for the shapes).

// Post a text, photo or video status (body: types/status.js CreateStatusBody). Sending the same idempotencyKey again
// returns the status that was already saved (so a retry after a lost answer never posts twice).
export async function createStatus(body) {
  const response = await apiClient.post('/api/statuses', body)
  return response.data.data
}

// My statuses that have not expired yet, oldest first, each with its view count.
export async function getMyStatuses() {
  const response = await apiClient.get('/api/statuses/mine')
  return response.data.data
}

// Everybody whose statuses I may see, grouped per person (people with something new first).
export async function getStatusFeed() {
  const response = await apiClient.get('/api/statuses/feed')
  return response.data.data
}

// "I saw this status." Safe to send twice.
export async function markStatusViewed(id) {
  await apiClient.post(`/api/statuses/${id}/view`)
}

// Who saw one of MY statuses.
export async function getStatusViewers(id) {
  const response = await apiClient.get(`/api/statuses/${id}/viewers`)
  return response.data.data
}

// A signed link (valid 5 minutes) to the photo / video of a status, or to the photo's small preview.
export async function getStatusMediaUrl(id, variant = 'original') {
  const response = await apiClient.get(`/api/statuses/${id}/media-url`, { params: { variant } })
  return response.data.data
}

// Delete one of my statuses (its photo / video is deleted too).
export async function deleteStatus(id) {
  await apiClient.delete(`/api/statuses/${id}`)
}

export async function getStatusPrivacy() {
  const response = await apiClient.get('/api/statuses/privacy')
  return response.data.data
}

// body: { mode, userIds }
export async function updateStatusPrivacy(body) {
  const response = await apiClient.put('/api/statuses/privacy', body)
  return response.data.data
}
