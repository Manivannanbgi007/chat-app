import * as authApi from '../../services/api/authApi'
import { sendOffline } from '../../services/api/presenceApi'
import { saveRefreshToken } from '../../services/api/tokenStorage'
import { useAuthStore } from '../../store/authStore'
import { fakeTokens } from '../../test/renderWithProviders'
import { logoutUser } from './authActions'

vi.mock('../../services/api/authApi')
vi.mock('../../services/api/presenceApi')

describe('logoutUser', () => {
  beforeEach(() => {
    vi.resetAllMocks()
    vi.mocked(authApi.logout).mockResolvedValue()
    useAuthStore.getState().setSession(fakeTokens(), true)
  })

  it('marks the device offline BEFORE the tokens are cleared (the call needs the access token)', async () => {
    const order: string[] = []
    vi.mocked(sendOffline).mockImplementation(() => {
      // at this moment the access token must still be there
      order.push(`offline (token ${useAuthStore.getState().accessToken})`)
    })
    vi.mocked(authApi.logout).mockImplementation(async () => {
      order.push('server logout')
    })
    const stopWatching = useAuthStore.subscribe((state, previous) => {
      if (previous.accessToken && !state.accessToken) order.push('tokens cleared')
    })

    await logoutUser()
    stopWatching()

    expect(order).toEqual(['offline (token access-1)', 'server logout', 'tokens cleared'])
  })

  it('still logs out locally when the server cannot be reached', async () => {
    vi.mocked(authApi.logout).mockRejectedValue(new Error('network'))

    await logoutUser()

    expect(sendOffline).toHaveBeenCalledTimes(1)
    expect(useAuthStore.getState().accessToken).toBeNull()
  })

  it('works without a refresh token (nothing to tell the auth service)', async () => {
    useAuthStore.getState().clearSession()
    useAuthStore.getState().setAccessToken('only-access')
    saveRefreshToken('', undefined)
    localStorage.clear()
    sessionStorage.clear()

    await logoutUser()

    expect(authApi.logout).not.toHaveBeenCalled()
    expect(useAuthStore.getState().accessToken).toBeNull()
  })
})
