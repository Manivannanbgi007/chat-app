import { create } from 'zustand'
import { clearRefreshToken, getRefreshToken, saveRefreshToken } from '../services/api/tokenStorage'
import { queryClient } from '../services/queryClient'
import type { AuthTokens, AuthUser } from '../types/auth'

// Login state of the app.
// The access token is kept in memory only. The refresh token is saved by tokenStorage.
interface AuthState {
  accessToken: string | null
  user: AuthUser | null
  // true while we try to get a new access token at start-up (show a loading screen)
  restoring: boolean
  setAccessToken: (token: string | null) => void
  setSession: (tokens: AuthTokens, remember?: boolean) => void
  updateUser: (changes: Partial<AuthUser>) => void
  setRestoring: (restoring: boolean) => void
  clearSession: () => void
}

export const useAuthStore = create<AuthState>((set) => ({
  accessToken: null,
  user: null,
  // If a refresh token is stored we will try to log in again before showing any page.
  restoring: getRefreshToken() !== null,

  setAccessToken: (token) => set({ accessToken: token }),

  setSession: (tokens, remember) => {
    saveRefreshToken(tokens.refreshToken, remember)
    set({ accessToken: tokens.accessToken, user: tokens.user, restoring: false })
  },

  updateUser: (changes) => set((state) => (state.user ? { user: { ...state.user, ...changes } } : state)),

  setRestoring: (restoring) => set({ restoring }),

  clearSession: () => {
    clearRefreshToken()
    queryClient.clear() // forget all server data of the previous user
    set({ accessToken: null, user: null, restoring: false })
  },
}))
