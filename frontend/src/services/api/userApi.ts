import type { ApiResponse } from '../../types/api'
import type { Contact, MyProfile, Privacy, PublicProfile } from '../../types/user'
import { apiClient } from './apiClient'

// user-service calls (all need a logged-in user). Each returns the "data" part of the envelope.

export async function getMyProfile(): Promise<MyProfile> {
  const response = await apiClient.get<ApiResponse<MyProfile>>('/api/users/me')
  return response.data.data
}

export async function updateMyProfile(body: { displayName: string; about?: string }): Promise<MyProfile> {
  const response = await apiClient.put<ApiResponse<MyProfile>>('/api/users/me', body)
  return response.data.data
}

export async function searchUsers(q: string): Promise<PublicProfile[]> {
  const response = await apiClient.get<ApiResponse<PublicProfile[]>>('/api/users/search', { params: { q } })
  return response.data.data
}

export async function getUserProfile(userId: string): Promise<PublicProfile> {
  const response = await apiClient.get<ApiResponse<PublicProfile>>(`/api/users/${userId}`)
  return response.data.data
}

export async function blockUser(userId: string): Promise<void> {
  await apiClient.post(`/api/users/${userId}/block`)
}

export async function unblockUser(userId: string): Promise<void> {
  await apiClient.delete(`/api/users/${userId}/block`)
}

export async function getBlockedUsers(): Promise<PublicProfile[]> {
  const response = await apiClient.get<ApiResponse<PublicProfile[]>>('/api/users/blocked')
  return response.data.data
}

export async function getPrivacy(): Promise<Privacy> {
  const response = await apiClient.get<ApiResponse<Privacy>>('/api/users/me/privacy')
  return response.data.data
}

export async function updatePrivacy(body: Privacy): Promise<Privacy> {
  const response = await apiClient.put<ApiResponse<Privacy>>('/api/users/me/privacy', body)
  return response.data.data
}

export async function getContacts(): Promise<Contact[]> {
  const response = await apiClient.get<ApiResponse<Contact[]>>('/api/contacts')
  return response.data.data
}

export async function addContact(body: { contactUserId: string; nickname?: string }): Promise<Contact> {
  const response = await apiClient.post<ApiResponse<Contact>>('/api/contacts', body)
  return response.data.data
}

export async function removeContact(contactId: string): Promise<void> {
  await apiClient.delete(`/api/contacts/${contactId}`)
}
