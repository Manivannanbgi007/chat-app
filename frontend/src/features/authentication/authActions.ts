import * as authApi from '../../services/api/authApi'
import { sendOffline } from '../../services/api/presenceApi'
import { endCallBeforeLogout } from '../calls/callRealtime'
import { refreshSession } from '../../services/api/refreshSession'
import { getRefreshToken } from '../../services/api/tokenStorage'
import { useAuthStore } from '../../store/authStore'
import { getDeviceName } from '../../utils/deviceName'

// The three things the app does with the login state.

// Log in and remember the session. Errors are thrown as AppError for the page to show.
export async function loginUser(identifier: string, password: string, rememberMe: boolean): Promise<void> {
  const tokens = await authApi.login({ identifier, password, deviceName: getDeviceName() })
  useAuthStore.getState().setSession(tokens, rememberMe)
}

// Tell the server to end this device's session, then forget everything locally.
// Even if the server cannot be reached the user is logged out on this device.
export async function logoutUser(): Promise<void> {
  const refreshToken = getRefreshToken()
  // Hang up a running call first: ending it on the server needs the access token, which is cleared below.
  endCallBeforeLogout()
  // Then tell presence-service that this device is gone (it needs the access token too).
  // The server keeps the user online if another device is still alive.
  sendOffline()
  try {
    if (refreshToken) {
      await authApi.logout(refreshToken)
    }
  } catch {
    // Ignore: local logout still happens below.
  }
  useAuthStore.getState().clearSession()
}

// Called once when the app starts: if a refresh token is stored, swap it for a new access token.
// (refreshSession is single-flight, so React StrictMode calling this twice sends only one request.)
export async function restoreSession(): Promise<void> {
  if (!getRefreshToken()) {
    useAuthStore.getState().setRestoring(false)
    return
  }
  try {
    await refreshSession()
  } catch {
    // Not logged in (token expired, or the server is unreachable): show the login page.
    useAuthStore.getState().setRestoring(false)
  }
}
