import { apiClient } from './apiClient'

// auth-service calls (API_DOCUMENTATION.md section 2).
// Every function returns the "data" part of the ApiResponse envelope (see types/auth.js for the shapes).

// GET /api/auth/ping goes through the gateway to auth-service.
export async function pingAuthService() {
  const response = await apiClient.get('/api/auth/ping')
  return response.data.data
}

// body: { username, email, phone?, password, displayName }
export async function register(body) {
  const response = await apiClient.post('/api/auth/register', body)
  return response.data.data
}

// body: { identifier, otp }
export async function verifyOtp(body) {
  await apiClient.post('/api/auth/verify-otp', { ...body, purpose: 'EMAIL_VERIFY' })
}

export async function resendOtp(identifier) {
  await apiClient.post('/api/auth/resend-otp', { identifier, purpose: 'EMAIL_VERIFY' })
}

// body: { identifier, password, deviceName } -> the tokens and the user (types/auth.js AuthTokens)
export async function login(body) {
  const response = await apiClient.post('/api/auth/login', body)
  return response.data.data
}

export async function logout(refreshToken) {
  await apiClient.post('/api/auth/logout', { refreshToken })
}

export async function forgotPassword(identifier) {
  await apiClient.post('/api/auth/forgot-password', { identifier })
}

// body: { token, newPassword }
export async function resetPassword(body) {
  await apiClient.post('/api/auth/reset-password', body)
}

export async function getSessions() {
  const response = await apiClient.get('/api/auth/sessions')
  return response.data.data
}

export async function deleteSession(sessionId) {
  await apiClient.delete(`/api/auth/sessions/${sessionId}`)
}
