import { useAuthStore } from '../../store/authStore'
import type { ApiResponse } from '../../types/api'
import type { Presence } from '../../types/presence'
import { apiClient } from './apiClient'

// presence-service calls (API_DOCUMENTATION.md section 7.1).

// "This device is alive." Sent every 20 seconds while the app is open and visible.
export async function sendHeartbeat(): Promise<void> {
  await apiClient.post('/api/presence/heartbeat')
}

// Is this person online, or when were they last seen? (visible = false when their privacy hides it)
export async function getPresence(userId: string): Promise<Presence> {
  const response = await apiClient.get<ApiResponse<Presence>>(`/api/presence/${userId}`)
  return response.data.data
}

// "This device is gone now." Used on logout and when the page closes.
//
// It uses fetch with keepalive (the browser finishes the request even while the page is closing).
// axios cannot do that, and navigator.sendBeacon cannot send the Authorization header.
// The header is read right here, at the moment of the call, so it still works when the caller clears the
// tokens straight afterwards (logout). Failures are ignored: the server notices a silent device after 60 seconds anyway.
export function sendOffline(): void {
  const token = useAuthStore.getState().accessToken
  if (!token) return
  try {
    const base = import.meta.env.VITE_API_BASE_URL ?? ''
    void fetch(`${base}/api/presence/offline`, {
      method: 'POST',
      keepalive: true,
      headers: { Authorization: `Bearer ${token}`, 'X-Correlation-Id': crypto.randomUUID() },
    }).catch(() => undefined)
  } catch {
    // fetch is missing or refused: nothing to do.
  }
}
