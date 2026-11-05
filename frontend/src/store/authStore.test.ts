import { getRefreshToken } from '../services/api/tokenStorage'
import { fakeTokens } from '../test/renderWithProviders'
import { useAuthStore } from './authStore'

describe('authStore', () => {
  beforeEach(() => useAuthStore.getState().clearSession())

  it('keeps the access token in memory and the user in the store', () => {
    useAuthStore.getState().setSession(fakeTokens(), true)
    expect(useAuthStore.getState().accessToken).toBe('access-1')
    expect(useAuthStore.getState().user?.displayName).toBe('Asha Rao')
    // the access token is never written to browser storage
    expect(JSON.stringify({ ...localStorage })).not.toContain('access-1')
    expect(JSON.stringify({ ...sessionStorage })).not.toContain('access-1')
  })

  it('"Remember me" puts the refresh token in localStorage', () => {
    useAuthStore.getState().setSession(fakeTokens(), true)
    expect(localStorage.length).toBe(1)
    expect(sessionStorage.length).toBe(0)
    expect(getRefreshToken()).toBe('refresh-1')
  })

  it('without "Remember me" the refresh token goes to sessionStorage', () => {
    useAuthStore.getState().setSession(fakeTokens(), false)
    expect(sessionStorage.length).toBe(1)
    expect(localStorage.length).toBe(0)
  })

  it('a rotated refresh token stays in the storage the user chose', () => {
    useAuthStore.getState().setSession(fakeTokens(), true)
    useAuthStore.getState().setSession(fakeTokens({ refreshToken: 'refresh-2' })) // like after a refresh
    expect(localStorage.length).toBe(1)
    expect(sessionStorage.length).toBe(0)
    expect(getRefreshToken()).toBe('refresh-2')
  })

  it('clearSession forgets the token, the user and the stored refresh token', () => {
    useAuthStore.getState().setSession(fakeTokens(), true)
    useAuthStore.getState().clearSession()
    expect(useAuthStore.getState().accessToken).toBeNull()
    expect(useAuthStore.getState().user).toBeNull()
    expect(getRefreshToken()).toBeNull()
  })

  it('updateUser changes the shown name', () => {
    useAuthStore.getState().setSession(fakeTokens(), false)
    useAuthStore.getState().updateUser({ displayName: 'Asha R.' })
    expect(useAuthStore.getState().user?.displayName).toBe('Asha R.')
  })
})
