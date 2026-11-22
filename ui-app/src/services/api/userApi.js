import { apiClient } from './apiClient'

// user-service calls (all need a logged-in user). Each returns the "data" part of the envelope
// (see types/user.js for the shapes).

export async function getMyProfile() {
  const response = await apiClient.get('/api/users/me')
  return response.data.data
}

// body: { displayName, about? }
export async function updateMyProfile(body) {
  const response = await apiClient.put('/api/users/me', body)
  return response.data.data
}

export async function searchUsers(q) {
  const response = await apiClient.get('/api/users/search', { params: { q } })
  return response.data.data
}

export async function getUserProfile(userId) {
  const response = await apiClient.get(`/api/users/${userId}`)
  return response.data.data
}

export async function blockUser(userId) {
  await apiClient.post(`/api/users/${userId}/block`)
}

export async function unblockUser(userId) {
  await apiClient.delete(`/api/users/${userId}/block`)
}

export async function getBlockedUsers() {
  const response = await apiClient.get('/api/users/blocked')
  return response.data.data
}

export async function getPrivacy() {
  const response = await apiClient.get('/api/users/me/privacy')
  return response.data.data
}

// body: { lastSeen, profilePhoto, about, groupAdd, readReceipts }
export async function updatePrivacy(body) {
  const response = await apiClient.put('/api/users/me/privacy', body)
  return response.data.data
}

export async function getContacts() {
  const response = await apiClient.get('/api/contacts')
  return response.data.data
}

// body: { contactUserId, nickname? }
export async function addContact(body) {
  const response = await apiClient.post('/api/contacts', body)
  return response.data.data
}

export async function removeContact(contactId) {
  await apiClient.delete(`/api/contacts/${contactId}`)
}
