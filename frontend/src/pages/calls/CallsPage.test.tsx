import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { callEngine } from '../../features/calls/callEngine'
import { useCallStore } from '../../features/calls/callStore'
import { getCallHistory } from '../../services/api/callApi'
import { makeCall } from '../../test/callFixtures'
import { profile } from '../../test/chatFixtures'
import { renderWithProviders } from '../../test/renderWithProviders'
import CallsPage from './CallsPage'

vi.mock('../../services/api/callApi')
vi.mock('../../features/calls/callEngine', () => ({ callEngine: { startCall: vi.fn() } }))

const meena = profile('u-meena', 'Meena Iyer')

beforeEach(() => {
  vi.clearAllMocks()
  useCallStore.getState().reset()
})

describe('CallsPage', () => {
  it('lists calls with their outcome and shows missed calls in red', async () => {
    vi.mocked(getCallHistory).mockResolvedValue({
      items: [
        makeCall({ id: 'a', status: 'ENDED', durationSeconds: 125 }),
        makeCall({ id: 'b', status: 'MISSED', direction: 'INCOMING', otherUser: meena, type: 'AUDIO' }),
      ],
      page: 0,
      size: 20,
      totalItems: 2,
      hasMore: false,
    })
    renderWithProviders(<CallsPage />)

    const rows = await screen.findAllByTestId('call-row')
    expect(rows).toHaveLength(2)
    expect(within(rows[0]).getByText('2:05')).toBeInTheDocument()
    expect(within(rows[0]).getByRole('img', { name: 'Outgoing' })).toBeInTheDocument()
    expect(within(rows[0]).getByRole('img', { name: 'Video call' })).toBeInTheDocument()
    expect(within(rows[1]).getByText('Meena Iyer')).toHaveClass('text-red-600')
    expect(within(rows[1]).getByText('Missed')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument()
  })

  it('calls the person back', async () => {
    vi.mocked(getCallHistory).mockResolvedValue({ items: [makeCall({ otherUser: meena })], page: 0, size: 20, totalItems: 1, hasMore: false })
    renderWithProviders(<CallsPage />)

    await userEvent.click(await screen.findByRole('button', { name: 'Video call Meena Iyer' }))
    expect(callEngine.startCall).toHaveBeenCalledWith(meena, 'VIDEO')
    await userEvent.click(screen.getByRole('button', { name: 'Voice call Meena Iyer' }))
    expect(callEngine.startCall).toHaveBeenCalledWith(meena, 'AUDIO')
  })

  it('loads more pages', async () => {
    vi.mocked(getCallHistory)
      .mockResolvedValueOnce({ items: [makeCall({ id: 'a' })], page: 0, size: 20, totalItems: 2, hasMore: true })
      .mockResolvedValueOnce({ items: [makeCall({ id: 'b', otherUser: meena })], page: 1, size: 20, totalItems: 2, hasMore: false })
    renderWithProviders(<CallsPage />)

    await userEvent.click(await screen.findByRole('button', { name: 'Load more' }))

    expect(await screen.findByText('Meena Iyer')).toBeInTheDocument()
    expect(getCallHistory).toHaveBeenLastCalledWith(1, 20)
    expect(screen.getAllByTestId('call-row')).toHaveLength(2)
    expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument()
  })

  it('says so when there are no calls, and reloads when a call ends', async () => {
    vi.mocked(getCallHistory).mockResolvedValue({ items: [], page: 0, size: 20, totalItems: 0, hasMore: false })
    renderWithProviders(<CallsPage />)

    expect(await screen.findByText(/No calls yet/)).toBeInTheDocument()
    useCallStore.getState().patch({ historyVersion: useCallStore.getState().historyVersion + 1 })
    await vi.waitFor(() => expect(getCallHistory).toHaveBeenCalledTimes(2))
  })
})
