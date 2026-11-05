import type { ApiResponse, PingData } from '../../types/api'
import type { AuthTokens, RegisterRequest, RegisterResult, SessionInfo } from '../../types/auth'
import { apiClient } from './apiClient'

// Every function returns the "data" part of the ApiResponse envelope.

// GET /api/auth/ping goes through the gateway to auth-service.
export async function pingAuthService(): Promise<PingData> {
  const response = await apiClient.get<ApiResponse<PingData>>('/api/auth/ping')
  return response.data.data
}

export async function register(body: RegisterRequest): Promise<RegisterResult> {
  const response = await apiClient.post<ApiResponse<RegisterResult>>('/api/auth/register', body)
  return response.data.data
}

export async function verifyOtp(body: { identifier: string; otp: string }): Promise<void> {
  await apiClient.post('/api/auth/verify-otp', { ...body, purpose: 'EMAIL_VERIFY' })
}

export async function resendOtp(identifier: string): Promise<void> {
  await apiClient.post('/api/auth/resend-otp', { identifier, purpose: 'EMAIL_VERIFY' })
}

export async function login(body: {
  identifier: string
  password: string
  deviceName: string
}): Promise<AuthTokens> {
  const response = await apiClient.post<ApiResponse<AuthTokens>>('/api/auth/login', body)
  return response.data.data
}

export async function logout(refreshToken: string): Promise<void> {
  await apiClient.post('/api/auth/logout', { refreshToken })
}

export async function forgotPassword(identifier: string): Promise<void> {
  await apiClient.post('/api/auth/forgot-password', { identifier })
}

export async function resetPassword(body: { token: string; newPassword: string }): Promise<void> {
  await apiClient.post('/api/auth/reset-password', body)
}

export async function getSessions(): Promise<SessionInfo[]> {
  const response = await apiClient.get<ApiResponse<SessionInfo[]>>('/api/auth/sessions')
  return response.data.data
}

export async function deleteSession(sessionId: string): Promise<void> {
  await apiClient.delete(`/api/auth/sessions/${sessionId}`)
}
