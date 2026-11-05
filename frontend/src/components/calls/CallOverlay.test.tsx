import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { callEngine } from '../../features/calls/callEngine'
import { useCallStore } from '../../features/calls/callStore'
import { makeCall } from '../../test/callFixtures'
import CallOverlay from './CallOverlay'

vi.mock('../../features/calls/callEngine', () => ({
  callEngine: {
    acceptIncoming: vi.fn(),
    declineIncoming: vi.fn(),
    hangUp: vi.fn(),
    toggleMute: vi.fn(),
    toggleCamera: vi.fn(),
    dismiss: vi.fn(),
  },
}))

const engine = vi.mocked(callEngine)
const overlay = () => screen.getByTestId('call-overlay')

function stream(withVideo: boolean) {
  return { getTracks: () => [], getAudioTracks: () => [], getVideoTracks: () => (withVideo ? [{}] : []) } as unknown as MediaStream
}

beforeEach(() => {
  vi.clearAllMocks()
  useCallStore.getState().reset()
})

describe('CallOverlay', () => {
  it('is hidden (but present for tests) when there is no call', () => {
    render(<CallOverlay />)
    expect(overlay()).toHaveAttribute('data-call-state', 'idle')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('rings: name, "Incoming video call", Accept and Decline', async () => {
    const call = makeCall({ direction: 'INCOMING' })
    useCallStore.getState().patch({ state: 'incoming', call, peer: call.otherUser, type: 'VIDEO' })
    render(<CallOverlay />)

    expect(overlay()).toHaveAttribute('data-call-state', 'incoming')
    expect(screen.getByRole('dialog', { name: 'Ravi Kumar' })).toBeInTheDocument()
    expect(screen.getByText('Incoming video call...')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Accept' }))
    expect(engine.acceptIncoming).toHaveBeenCalled()
    await userEvent.click(screen.getByRole('button', { name: 'Decline' }))
    expect(engine.declineIncoming).toHaveBeenCalled()
  })

  it('shows why answering did not work, and a busy Accept while the microphone starts', () => {
    useCallStore.getState().patch({
      state: 'incoming',
      call: makeCall({ direction: 'INCOMING' }),
      type: 'AUDIO',
      accepting: true,
      error: 'No microphone found on this device.',
    })
    render(<CallOverlay />)

    expect(screen.getByRole('alert')).toHaveTextContent('No microphone found on this device.')
    expect(screen.getByRole('button', { name: 'Accept' })).toBeDisabled()
    expect(screen.getByText(/Starting your microphone/)).toBeInTheDocument()
  })

  it('calling: "Calling..." and Cancel', async () => {
    useCallStore.getState().patch({ state: 'outgoing', peer: makeCall().otherUser, type: 'AUDIO' })
    render(<CallOverlay />)

    expect(screen.getByTestId('call-status')).toHaveTextContent('Calling... (voice call)')
    await userEvent.click(screen.getByRole('button', { name: 'Cancel call' }))
    expect(engine.hangUp).toHaveBeenCalled()
  })

  it('in a video call: remote video, my small video, timer, mute, camera and hang up', async () => {
    useCallStore.getState().patch({
      state: 'active',
      call: makeCall({ status: 'ACCEPTED', answeredAt: new Date(Date.now() - 65_000).toISOString() }),
      type: 'VIDEO',
      connectionState: 'connected',
      localStream: stream(true),
      remoteStream: stream(true),
      remoteVideo: true,
    })
    render(<CallOverlay />)

    expect(overlay()).toHaveAttribute('data-connection-state', 'connected')
    expect(screen.getByLabelText("Ravi Kumar's video")).toBeInTheDocument()
    expect(screen.getByLabelText('My video')).toBeInTheDocument()
    expect(screen.getByTestId('call-timer')).toHaveTextContent(/^1:0\d$/)

    await userEvent.click(screen.getByRole('button', { name: 'Mute' }))
    expect(engine.toggleMute).toHaveBeenCalled()
    await userEvent.click(screen.getByRole('button', { name: 'Turn camera off' }))
    expect(engine.toggleCamera).toHaveBeenCalled()
    await userEvent.click(screen.getByRole('button', { name: 'Hang up' }))
    expect(engine.hangUp).toHaveBeenCalled()
  })

  it('a voice call shows the picture of the person, plays their sound, and has no camera button', () => {
    useCallStore.getState().patch({
      state: 'active',
      call: makeCall({ type: 'AUDIO', status: 'ACCEPTED', answeredAt: new Date().toISOString() }),
      type: 'AUDIO',
      muted: true,
      localStream: stream(false),
      remoteStream: stream(false),
    })
    render(<CallOverlay />)

    expect(screen.getByTestId('remote-audio')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /camera/ })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Unmute' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('ended: says why, and Close dismisses it', async () => {
    useCallStore.getState().patch({ state: 'ended', call: makeCall(), endedText: 'Declined' })
    render(<CallOverlay />)

    expect(screen.getByTestId('call-status')).toHaveTextContent('Declined')
    await userEvent.click(screen.getByRole('button', { name: 'Close' }))
    expect(engine.dismiss).toHaveBeenCalled()
  })
})
