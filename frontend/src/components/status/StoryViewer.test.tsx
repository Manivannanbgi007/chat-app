import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import type { Mock } from 'vitest'
import type { ViewerGroup } from '../../features/status/storyViewerState'
import { deleteStatus, getStatusMediaUrl, getStatusViewers, markStatusViewed } from '../../services/api/statusApi'
import { profile } from '../../test/chatFixtures'
import { renderWithProviders } from '../../test/renderWithProviders'
import { makeMyStatus, makePhotoStatus, makeStatus, makeVideoStatus } from '../../test/statusFixtures'
import StoryViewer from './StoryViewer'

vi.mock('../../services/api/statusApi')

const asha = profile('u-asha', 'Asha Rao')
const ravi = profile('u-ravi', 'Ravi Kumar')
const me = profile('u-me', 'Me Myself')

let onViewed: Mock<(statusId: string) => void>
let onDeleted: Mock<(statusId: string) => void>
let onClose: Mock<() => void>

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(markStatusViewed).mockResolvedValue()
  vi.mocked(getStatusMediaUrl).mockImplementation(async (id, variant) => ({
    url: `https://storage.test/${id}/${variant}`,
    expiresAt: new Date(Date.now() + 300_000).toISOString(),
  }))
  onViewed = vi.fn()
  onDeleted = vi.fn()
  onClose = vi.fn()
})

afterEach(() => {
  vi.useRealTimers()
})

function open(groups: ViewerGroup[], startGroup = 0, startIndex = 0) {
  renderWithProviders(
    <StoryViewer
      groups={groups}
      startGroup={startGroup}
      startIndex={startIndex}
      onViewed={onViewed}
      onDeleted={onDeleted}
      onClose={onClose}
    />,
  )
  return screen.getByTestId('story-viewer')
}

const shownId = () => screen.getByTestId('story-viewer').getAttribute('data-status-id')

describe('StoryViewer', () => {
  it('shows a text status on its colour and moves on by itself after 5 seconds, then to the next person, then closes', () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const a1 = makeStatus({ text: 'Good morning', backgroundColor: '#7C3AED' })
    const r1 = makeStatus({ text: 'Ravi here' })
    open([
      { owner: asha, statuses: [a1], mine: false },
      { owner: ravi, statuses: [r1], mine: false },
    ])

    expect(screen.getByTestId('story-text')).toHaveTextContent('Good morning')
    expect(screen.getByTestId('story-text')).toHaveStyle({ backgroundColor: '#7C3AED' })
    expect(screen.getByText('Asha Rao')).toBeInTheDocument()

    act(() => vi.advanceTimersByTime(4900))
    expect(shownId()).toBe(a1.id)
    act(() => vi.advanceTimersByTime(200))
    expect(shownId()).toBe(r1.id)
    expect(screen.getByText('Ravi Kumar')).toBeInTheDocument()

    act(() => vi.advanceTimersByTime(5100))
    expect(onClose).toHaveBeenCalled()
  })

  it('marks every status of other people viewed once, and never my own', async () => {
    const [a1, a2] = [makeStatus(), makeStatus()]
    open([{ owner: asha, statuses: [a1, a2], mine: false }])

    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    fireEvent.click(screen.getByRole('button', { name: 'Previous' }))
    fireEvent.click(screen.getByRole('button', { name: 'Next' }))

    await waitFor(() => expect(markStatusViewed).toHaveBeenCalledTimes(2))
    expect(markStatusViewed).toHaveBeenNthCalledWith(1, a1.id)
    expect(markStatusViewed).toHaveBeenNthCalledWith(2, a2.id)
    expect(onViewed.mock.calls).toEqual([[a1.id], [a2.id]])
  })

  it('does not mark statuses I already saw, nor my own', () => {
    open([{ owner: asha, statuses: [makeStatus({ viewedByMe: true })], mine: false }])
    expect(markStatusViewed).not.toHaveBeenCalled()
  })

  it('never marks my own statuses', () => {
    open([{ owner: me, statuses: [makeMyStatus({ viewedByMe: false })], mine: true }])
    expect(markStatusViewed).not.toHaveBeenCalled()
    expect(screen.getByText('My status')).toBeInTheDocument()
  })

  it('moves with the Next / Previous areas and with the arrow keys, and closes with Escape', () => {
    const statuses = [makeStatus(), makeStatus(), makeStatus()]
    open([{ owner: asha, statuses, mine: false }])

    fireEvent.click(screen.getByRole('button', { name: 'Next' }))
    expect(shownId()).toBe(statuses[1].id)
    fireEvent.keyDown(document, { key: 'ArrowRight' })
    expect(shownId()).toBe(statuses[2].id)
    fireEvent.keyDown(document, { key: 'ArrowLeft' })
    expect(shownId()).toBe(statuses[1].id)
    fireEvent.click(screen.getByRole('button', { name: 'Previous' }))
    expect(shownId()).toBe(statuses[0].id)

    fireEvent.keyDown(document, { key: 'Escape' })
    expect(onClose).toHaveBeenCalled()
  })

  it('Space pauses and resumes', () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const statuses = [makeStatus(), makeStatus()]
    open([{ owner: asha, statuses, mine: false }])

    fireEvent.keyDown(document, { key: ' ' })
    expect(screen.getByTestId('story-viewer')).toHaveAttribute('data-paused', 'true')
    act(() => vi.advanceTimersByTime(8000))
    expect(shownId()).toBe(statuses[0].id)

    fireEvent.keyDown(document, { key: ' ' })
    act(() => vi.advanceTimersByTime(5000))
    expect(shownId()).toBe(statuses[1].id)
  })

  it('holding the pointer down pauses, and the release after a hold does not move on', () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'setTimeout', 'clearTimeout'] })
    const statuses = [makeStatus(), makeStatus()]
    open([{ owner: asha, statuses, mine: false }])
    const next = screen.getByRole('button', { name: 'Next' })

    fireEvent.pointerDown(next)
    act(() => vi.advanceTimersByTime(400)) // longer than a tap
    expect(screen.getByTestId('story-viewer')).toHaveAttribute('data-paused', 'true')
    act(() => vi.advanceTimersByTime(8000))
    expect(shownId()).toBe(statuses[0].id)

    fireEvent.pointerUp(next)
    fireEvent.click(next) // the browser's click after the hold
    expect(shownId()).toBe(statuses[0].id)
    expect(screen.getByTestId('story-viewer')).toHaveAttribute('data-paused', 'false')

    // a short tap still moves on
    fireEvent.pointerDown(next)
    fireEvent.pointerUp(next)
    fireEvent.click(next)
    expect(shownId()).toBe(statuses[1].id)
  })

  it('loads a photo through a signed link and starts its timer only once it has loaded', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const photo = makePhotoStatus({ caption: 'Sunset' })
    const after = makeStatus()
    open([{ owner: asha, statuses: [photo, after], mine: false }])

    const picture = await screen.findByTestId('story-photo')
    expect(picture).toHaveAttribute('src', `https://storage.test/${photo.id}/original`)
    expect(screen.getByText('Sunset')).toBeInTheDocument()
    act(() => vi.advanceTimersByTime(8000))
    expect(shownId()).toBe(photo.id) // still loading: no timer

    fireEvent.load(picture)
    act(() => vi.advanceTimersByTime(5100))
    expect(shownId()).toBe(after.id)
  })

  it('plays a video to its end and then moves on', async () => {
    const play = vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue()
    const pause = vi.spyOn(HTMLMediaElement.prototype, 'pause').mockImplementation(() => undefined)
    const video = makeVideoStatus()
    const after = makeStatus()
    open([{ owner: asha, statuses: [video, after], mine: false }])

    const player = await screen.findByTestId('story-video')
    expect(player).toHaveAttribute('src', `https://storage.test/${video.id}/original`)
    expect(play).toHaveBeenCalled()
    fireEvent.ended(player)
    expect(shownId()).toBe(after.id)
    play.mockRestore()
    pause.mockRestore()
  })

  it('shows my view count and the list of viewers', async () => {
    vi.mocked(getStatusViewers).mockResolvedValue({
      viewersHidden: false,
      viewCount: 2,
      viewers: [
        { user: ravi, viewedAt: new Date().toISOString() },
        { user: asha, viewedAt: new Date().toISOString() },
      ],
    })
    const mine = makeMyStatus({ viewCount: 2 })
    open([{ owner: me, statuses: [mine], mine: true }])

    expect(screen.getByTestId('status-view-count')).toHaveTextContent('2')
    fireEvent.click(screen.getByRole('button', { name: 'Viewers' }))

    const list = await screen.findByRole('list', { name: 'People who viewed this status' })
    expect(within(list).getByText('Ravi Kumar')).toBeInTheDocument()
    expect(within(list).getByText('Asha Rao')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: /Viewed by 2/ })).toBeInTheDocument()
    expect(getStatusViewers).toHaveBeenCalledWith(mine.id)
    expect(screen.getByTestId('story-viewer')).toHaveAttribute('data-paused', 'true')

    fireEvent.keyDown(document, { key: 'Escape' }) // closes only the list
    expect(screen.queryByRole('dialog', { name: 'Viewers' })).not.toBeInTheDocument()
    expect(onClose).not.toHaveBeenCalled()
  })

  it('explains hidden viewers when my read receipts are off', async () => {
    vi.mocked(getStatusViewers).mockResolvedValue({ viewersHidden: true, viewCount: 0, viewers: [] })
    open([{ owner: me, statuses: [makeMyStatus()], mine: true }])

    fireEvent.click(screen.getByRole('button', { name: 'Viewers' }))
    expect(await screen.findByText(/Viewers are hidden because your read receipts are off/)).toBeInTheDocument()
  })

  it('deletes one of my statuses after confirmation and closes when none is left', async () => {
    vi.mocked(deleteStatus).mockResolvedValue()
    const [first, second] = [makeMyStatus(), makeMyStatus()]
    open([{ owner: me, statuses: [first, second], mine: true }])

    fireEvent.click(screen.getByRole('button', { name: 'Delete status' }))
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Delete' }))

    await waitFor(() => expect(shownId()).toBe(second.id))
    expect(deleteStatus).toHaveBeenCalledWith(first.id)
    expect(onDeleted).toHaveBeenCalledWith(first.id)

    fireEvent.click(screen.getByRole('button', { name: 'Delete status' }))
    fireEvent.click(within(screen.getByRole('alertdialog')).getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(onClose).toHaveBeenCalled())
  })

  it('has no delete or viewers buttons on other people\'s statuses', () => {
    open([{ owner: asha, statuses: [makeStatus()], mine: false }])
    expect(screen.queryByRole('button', { name: 'Delete status' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Viewers' })).not.toBeInTheDocument()
  })
})
