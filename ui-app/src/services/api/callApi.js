import { useAuthStore } from '../../store/authStore'
import { apiClient } from './apiClient'

// call-signaling-service calls (API_DOCUMENTATION.md section 7.3). Each returns the "data" part of the envelope.
// The device that sends a request is the login session of the access token, so the server knows which device
// started or answered a call.

// Start a call. Status RINGING, or BUSY (the other person is already in a call; nothing rings).
// type: 'AUDIO' | 'VIDEO'. Answers { call, iceServers } (types/call.js CallSession).
export async function startCall(calleeId, type) {
  const response = await apiClient.post('/api/calls', { calleeId, type })
  return response.data.data
}

// My call that is ringing or in progress right now (null when there is none).
export async function getActiveCall() {
  const response = await apiClient.get('/api/calls/active')
  return response.data.data ?? null
}

// Every call I made or received, newest first.
export async function getCallHistory(page = 0, size = 20) {
  const response = await apiClient.get('/api/calls/history', { params: { page, size } })
  return response.data.data
}

export async function getCall(id) {
  const response = await apiClient.get(`/api/calls/${id}`)
  return response.data.data
}

// Answer on THIS device. The other devices of mine stop ringing.
export async function acceptCall(id) {
  const response = await apiClient.post(`/api/calls/${id}/accept`)
  return response.data.data
}

export async function rejectCall(id) {
  const response = await apiClient.post(`/api/calls/${id}/reject`)
  return response.data.data
}

// Hang up / cancel. Safe to send twice: an already finished call is returned unchanged.
export async function endCall(id) {
  const response = await apiClient.post(`/api/calls/${id}/end`)
  return response.data.data
}

// Offer, answer or a batch of ICE candidates for the other person's browser (relayed, never stored).
// body: { type: 'OFFER' | 'ANSWER' | 'ICE', sdp?, candidates? }
export async function sendSignal(id, body) {
  await apiClient.post(`/api/calls/${id}/signal`, body)
}

// "My side of the call is still alive" (every 10 seconds during a call).
export async function pingCall(id) {
  await apiClient.post(`/api/calls/${id}/ping`)
}

// Hang up while the page is closing. Uses fetch with keepalive (the browser finishes the request even while the page
// goes away); axios cannot do that and navigator.sendBeacon cannot send the Authorization header.
// Same idea as sendOffline() in presenceApi.js. Failures are ignored: the server ends a silent call after 30 seconds.
export function endCallKeepalive(id) {
  const token = useAuthStore.getState().accessToken
  if (!token) return
  try {
    const base = import.meta.env.VITE_API_BASE_URL ?? ''
    fetch(`${base}/api/calls/${id}/end`, {
      method: 'POST',
      keepalive: true,
      headers: { Authorization: `Bearer ${token}`, 'X-Correlation-Id': crypto.randomUUID() },
    }).catch(() => undefined)
  } catch {
    // fetch is missing or refused: nothing to do.
  }
}
