import { create } from 'zustand'
import { clearRefreshToken, getRefreshToken, saveRefreshToken } from '../services/api/tokenStorage'
import { queryClient } from '../services/queryClient'

// Login state of the app.
// The access token is kept in memory only. The refresh token is saved by tokenStorage.
//
// State:  accessToken (string | null), user (types/auth.js AuthUser | null),
//         restoring (true while we try to get a new access token at start-up: show a loading screen)
export const useAuthStore = create((set) => ({
  accessToken: null,
  user: null,
  // If a refresh token is stored we will try to log in again before showing any page.
  restoring: getRefreshToken() !== null,

  setAccessToken: (token) => set({ accessToken: token }),

  // tokens: the login / refresh answer. remember: true/false right after login, undefined after a refresh.
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
