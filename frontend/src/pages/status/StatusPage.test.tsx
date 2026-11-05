import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AppError } from '../../services/api/appError'
import { createStatus, getMyStatuses, getStatusFeed, markStatusViewed } from '../../services/api/statusApi'
import { useAuthStore } from '../../store/authStore'
import { fakeTokens, renderWithProviders } from '../../test/renderWithProviders'
import { makeGroup, makeMyStatus, makeStatus } from '../../test/statusFixtures'
import StatusPage from './StatusPage'

vi.mock('../../services/api/statusApi')

beforeEach(() => {
  vi.resetAllMocks()
  useAuthStore.getState().setSession(fakeTokens(), false)
  vi.mocked(getMyStatuses).mockResolvedValue([])
  vi.mocked(getStatusFeed).mockResolvedValue([])
  vi.mocked(markStatusViewed).mockResolvedValue()
})

afterEach(() => {
  vi.useRealTimers()
  useAuthStore.getState().clearSession()
})

describe('StatusPage', () => {
  it('splits people into recent and viewed updates', async () => {
    vi.mocked(getStatusFeed).mockResolvedValue([
      makeGroup('u-ravi', 'Ravi Kumar', [makeStatus({ viewedByMe: true }), makeStatus()]),
      makeGroup('u-meena', 'Meena Iyer', [makeStatus({ viewedByMe: true })]),
    ])
    renderWithProviders(<StatusPage />)

    const recent = await screen.findByTestId('recent-updates')
    expect(within(recent).getByRole('button', { name: 'Ravi Kumar' })).toBeInTheDocument()
    const viewed = screen.getByTestId('viewed-updates')
    expect(within(viewed).getByRole('button', { name: 'Meena Iyer' })).toBeInTheDocument()
    // one ring segment per status: Ravi has one seen (grey) and one new (green)
    const segments = within(recent).getByTestId('status-ring').querySelectorAll('circle')
    expect([...segments].map((circle) => circle.getAttribute('data-highlight'))).toEqual(['false', 'true'])
  })

  it('invites me to add a status when I have none, and says so when nobody posted anything', async () => {
    renderWithProviders(<StatusPage />)
    const mine = screen.getByTestId('my-status')
    expect(await within(mine).findByText(/Add a status update/)).toBeInTheDocument()
    expect(await screen.findByText(/No status updates from your contacts yet/)).toBeInTheDocument()
    expect(screen.queryByTestId('recent-updates')).not.toBeInTheDocument()
  })

  it('shows my status with the number of updates and the views of the latest, and opens it in the viewer', async () => {
    vi.mocked(getMyStatuses).mockResolvedValue([makeMyStatus({ viewCount: 5 }), makeMyStatus({ viewCount: 3 })])
    renderWithProviders(<StatusPage />)

    const mine = screen.getByTestId('my-status')
    expect(await within(mine).findByText(/2 updates/)).toBeInTheDocument()
    expect(within(mine).getByTestId('my-view-count')).toHaveTextContent('3 views')

    await userEvent.click(within(mine).getByRole('button', { name: 'View my status' }))
    const viewer = screen.getByTestId('story-viewer')
    expect(within(viewer).getByText('My status')).toBeInTheDocument()
    expect(markStatusViewed).not.toHaveBeenCalled()
  })

  it('opening someone marks their statuses viewed and moves them to "Viewed updates"', async () => {
    const status = makeStatus()
    vi.mocked(getStatusFeed).mockResolvedValue([makeGroup('u-ravi', 'Ravi Kumar', [status])])
    renderWithProviders(<StatusPage />)

    await userEvent.click(await screen.findByRole('button', { name: 'Ravi Kumar' }))
    expect(screen.getByTestId('story-viewer')).toHaveAttribute('data-status-id', status.id)
    await waitFor(() => expect(markStatusViewed).toHaveBeenCalledWith(status.id))

    fireEvent.keyDown(document, { key: 'Escape' })
    expect(screen.queryByTestId('story-viewer')).not.toBeInTheDocument()
    expect(within(screen.getByTestId('viewed-updates')).getByRole('button', { name: 'Ravi Kumar' })).toBeInTheDocument()
    expect(screen.queryByTestId('recent-updates')).not.toBeInTheDocument()
  })

  it('asks for the lists again every 30 seconds while open, and stops when the page closes', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const { unmount } = renderWithProviders(<StatusPage />)
    await waitFor(() => expect(getStatusFeed).toHaveBeenCalledTimes(1))

    await act(async () => vi.advanceTimersByTime(30_000))
    await waitFor(() => expect(getStatusFeed).toHaveBeenCalledTimes(2))
    expect(getMyStatuses).toHaveBeenCalledTimes(2)

    unmount()
    await act(async () => vi.advanceTimersByTime(90_000))
    expect(getStatusFeed).toHaveBeenCalledTimes(2)
  })

  it('posts a text status and refreshes my status', async () => {
    const posted = makeMyStatus({ text: 'Hello all' })
    vi.mocked(createStatus).mockResolvedValue(posted)
    renderWithProviders(<StatusPage />)
    await waitFor(() => expect(getMyStatuses).toHaveBeenCalledTimes(1))

    vi.mocked(getMyStatuses).mockResolvedValue([posted])
    await userEvent.click(screen.getByRole('button', { name: 'Add text status' }))
    await userEvent.type(screen.getByRole('textbox', { name: 'Status text' }), 'Hello all')
    await userEvent.click(screen.getByRole('button', { name: 'Post' }))

    expect(screen.queryByTestId('text-status-composer')).not.toBeInTheDocument()
    expect(await within(screen.getByTestId('my-status')).findByText(/1 update/)).toBeInTheDocument()
    expect(getMyStatuses).toHaveBeenCalledTimes(2)
  })

  it('refuses files that are not photos or videos, and opens the composer for a photo', async () => {
    renderWithProviders(<StatusPage />)
    const input = screen.getByLabelText('Photo or video file')

    await userEvent.upload(input, new File(['%PDF'], 'report.pdf', { type: 'application/pdf' }), { applyAccept: false })
    expect(await screen.findByRole('alert')).toHaveTextContent('Only photos and videos can be posted as a status.')

    await userEvent.upload(input, new File(['png'], 'sea.png', { type: 'image/png' }))
    expect(screen.getByRole('dialog', { name: 'New photo status' })).toBeInTheDocument()
  })

  it('shows a loading error', async () => {
    vi.mocked(getStatusFeed).mockRejectedValue(new AppError('SERVER', 'The server had a problem. Please try again later.', 503))
    renderWithProviders(<StatusPage />)
    expect(await screen.findByRole('alert')).toHaveTextContent('The server had a problem')
  })

  it('links to the status privacy settings', () => {
    renderWithProviders(<StatusPage />)
    expect(screen.getByRole('link', { name: /Status privacy/ })).toHaveAttribute('href', '/settings#status-privacy')
  })
})
