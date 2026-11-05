import axios from 'axios'
import { useAuthStore } from '../../store/authStore'
import type { ApiResponse } from '../../types/api'
import type { AuthTokens } from '../../types/auth'
import { AppError, toAppError } from './appError'
import { getRefreshToken } from './tokenStorage'

// A separate, bare axios instance for the refresh call.
// It has no 401 handling, so a failed refresh can never trigger another refresh.
export const refreshClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '',
  timeout: 15000,
})

// The refresh call that is running right now (if any).
let inFlight: Promise<string> | null = null

// Gets a new access token using the stored refresh token. Returns the new access token.
//
// IMPORTANT: refresh tokens are single-use. The server treats a second use of an old token
// as theft and ends the whole login session. So when many requests fail with 401 at the
// same time, they must all share ONE refresh call. That is what `inFlight` does.
export function refreshSession(): Promise<string> {
  if (!inFlight) {
    inFlight = doRefresh().finally(() => {
      inFlight = null
    })
  }
  return inFlight
}

async function doRefresh(): Promise<string> {
  const refreshToken = getRefreshToken()
  if (!refreshToken) {
    throw new AppError('UNAUTHORIZED', 'Your session has expired. Please log in again.', 401)
  }

  try {
    const response = await refreshClient.post<ApiResponse<AuthTokens>>(
      '/api/auth/refresh',
      { refreshToken },
      { headers: { 'X-Correlation-Id': crypto.randomUUID() } },
    )
    const tokens = response.data.data
    // The refresh token has changed (rotation): save the new one, keep the old storage choice.
    useAuthStore.getState().setSession(tokens)
    return tokens.accessToken
  } catch (error) {
    const appError = toAppError(error)
    // Only a real "token rejected" answer ends the session.
    // A network problem should not log the user out.
    if (appError.status === 401) {
      useAuthStore.getState().clearSession()
    }
    throw appError
  }
}
