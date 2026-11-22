import axios, { isAxiosError } from 'axios'
import { useAuthStore } from '../../store/authStore'
import { toAppError } from './appError'
import { refreshSession } from './refreshSession'

// The one axios instance used by the whole app.
// Empty base URL = same origin (Vite proxy in dev, nginx in Docker) -> API gateway.
export const apiClient = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL ?? '',
  timeout: 15000,
})

// Before every request: add a correlation id and (if logged in) the access token.
apiClient.interceptors.request.use((config) => {
  config.headers.set('X-Correlation-Id', crypto.randomUUID())

  const token = useAuthStore.getState().accessToken
  if (token) {
    config.headers.set('Authorization', `Bearer ${token}`)
  }
  return config
})

// Login, register, refresh... are public. A 401 there means "wrong credentials", not "token expired".
// /api/auth/sessions is the exception: it needs a logged-in user.
function isPublicAuthUrl(url) {
  return !!url && url.startsWith('/api/auth/') && !url.startsWith('/api/auth/sessions')
}

// After every response:
// - 401 on a protected call -> get a new access token (one shared refresh) and retry the call once.
//   (`_retried` is our own flag on the request config, so a request is never retried twice.)
// - any other failure becomes an AppError (see appError.js).
apiClient.interceptors.response.use(
  (response) => response,
  async (error) => {
    const config = isAxiosError(error) ? error.config : undefined

    if (config && error.response?.status === 401 && !config._retried && !isPublicAuthUrl(config.url)) {
      config._retried = true
      try {
        await refreshSession() // many failed calls share one refresh
        return await apiClient.request(config) // the request interceptor adds the new token
      } catch (retryError) {
        // If the refresh failed, refreshSession already logged the user out (ProtectedRoute shows /login).
        return Promise.reject(toAppError(retryError))
      }
    }

    return Promise.reject(toAppError(error))
  },
)
