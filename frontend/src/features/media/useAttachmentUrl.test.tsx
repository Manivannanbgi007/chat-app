import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import * as chatApi from '../../services/api/chatApi'
import { useAttachmentUrl } from './useAttachmentUrl'

vi.mock('../../services/api/chatApi')
const api = vi.mocked(chatApi)

function wrapper({ children }: { children: ReactNode }) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return <QueryClientProvider client={client}>{children}</QueryClientProvider>
}

const inMinutes = (minutes: number) => new Date(Date.now() + minutes * 60_000).toISOString()

beforeEach(() => vi.resetAllMocks())

describe('useAttachmentUrl', () => {
  it('fetches the signed link of the right variant', async () => {
    api.getAttachmentUrl.mockResolvedValue({ url: 'https://storage/thumb', expiresAt: inMinutes(5) })

    const { result } = renderHook(() => useAttachmentUrl('m1', 'thumbnail'), { wrapper })

    expect(result.current.loading).toBe(true)
    await waitFor(() => expect(result.current.url).toBe('https://storage/thumb'))
    expect(api.getAttachmentUrl).toHaveBeenCalledWith('m1', 'thumbnail')
  })

  it('does not ask for anything while disabled (for example a message that is still being sent)', () => {
    renderHook(() => useAttachmentUrl('local-1', 'original', false), { wrapper })

    expect(api.getAttachmentUrl).not.toHaveBeenCalled()
  })

  it('reports a failure', async () => {
    api.getAttachmentUrl.mockRejectedValue(new Error('no'))

    const { result } = renderHook(() => useAttachmentUrl('m1', 'original'), { wrapper })

    // the hook tries once more after a second before it gives up
    await waitFor(() => expect(result.current.failed).toBe(true), { timeout: 4000 })
    expect(result.current.url).toBeNull()
  })

  it('reload() fetches a new link once when the element could not load the file, but not again for the same link', async () => {
    api.getAttachmentUrl
      .mockResolvedValueOnce({ url: 'https://storage/old', expiresAt: inMinutes(5) })
      .mockResolvedValueOnce({ url: 'https://storage/new', expiresAt: inMinutes(5) })
      .mockResolvedValue({ url: 'https://storage/newer', expiresAt: inMinutes(5) })
    const { result } = renderHook(() => useAttachmentUrl('m1', 'original'), { wrapper })
    await waitFor(() => expect(result.current.url).toBe('https://storage/old'))

    act(() => result.current.reload()) // e.g. the picture got a 403 because the link expired
    await waitFor(() => expect(result.current.url).toBe('https://storage/new'))
    expect(api.getAttachmentUrl).toHaveBeenCalledTimes(2)

    act(() => result.current.reload())
    act(() => result.current.reload()) // a second error on the same new link must not loop forever
    await waitFor(() => expect(api.getAttachmentUrl).toHaveBeenCalledTimes(3))
    act(() => result.current.reload())
    expect(api.getAttachmentUrl.mock.calls.length).toBeLessThanOrEqual(3)
  })

  it('asks for a new link when the cached one has expired', async () => {
    // The first link is already (almost) expired, so it counts as stale at once.
    api.getAttachmentUrl
      .mockResolvedValueOnce({ url: 'https://storage/old', expiresAt: new Date(Date.now() + 5_000).toISOString() })
      .mockResolvedValue({ url: 'https://storage/fresh', expiresAt: inMinutes(5) })
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const shared = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>

    const first = renderHook(() => useAttachmentUrl('m1', 'original'), { wrapper: shared })
    await waitFor(() => expect(first.result.current.url).toBe('https://storage/old'))
    first.unmount()

    // another bubble (or the lightbox) asks for the same file later: the stale link is replaced
    const second = renderHook(() => useAttachmentUrl('m1', 'original'), { wrapper: shared })
    await waitFor(() => expect(second.result.current.url).toBe('https://storage/fresh'))
    expect(api.getAttachmentUrl).toHaveBeenCalledTimes(2)
  })

  it('reuses a link that is still fresh instead of asking again', async () => {
    api.getAttachmentUrl.mockResolvedValue({ url: 'https://storage/one', expiresAt: inMinutes(5) })
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const shared = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>

    const first = renderHook(() => useAttachmentUrl('m1', 'original'), { wrapper: shared })
    await waitFor(() => expect(first.result.current.url).toBe('https://storage/one'))
    first.unmount()
    const second = renderHook(() => useAttachmentUrl('m1', 'original'), { wrapper: shared })

    expect(second.result.current.url).toBe('https://storage/one')
    expect(api.getAttachmentUrl).toHaveBeenCalledTimes(1)
  })
})
