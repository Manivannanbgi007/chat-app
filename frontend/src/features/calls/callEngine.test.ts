import { AxiosError, AxiosHeaders } from 'axios'
import { callEvent, ICE_SERVERS, makeCall, makeSession } from '../../test/callFixtures'
import { OTHER, profile } from '../../test/chatFixtures'
import type { Call, CallSession, IceCandidateData } from '../../types/call'
import { CallEngine, type CallEngineDeps, type PeerConnectionLike } from './callEngine'
import { useCallStore } from './callStore'

// ---- fakes ----

class FakePeerConnection implements PeerConnectionLike {
  connectionState = 'new'
  remoteDescription: unknown = null
  localDescription: unknown = null
  onicecandidate: PeerConnectionLike['onicecandidate'] = null
  ontrack: PeerConnectionLike['ontrack'] = null
  onconnectionstatechange: PeerConnectionLike['onconnectionstatechange'] = null
  tracks: MediaStreamTrack[] = []
  closed = false
  config: RTCConfiguration

  constructor(config: RTCConfiguration) {
    this.config = config
  }

  addTrack(track: MediaStreamTrack) {
    this.tracks.push(track)
  }
  createOffer = vi.fn(async () => ({ type: 'offer', sdp: 'offer-sdp' }))
  createAnswer = vi.fn(async () => ({ type: 'answer', sdp: 'answer-sdp' }))
  setLocalDescription = vi.fn(async (description: unknown) => {
    this.localDescription = description
  })
  setRemoteDescription = vi.fn(async (description: unknown) => {
    this.remoteDescription = description
  })
  addIceCandidate = vi.fn(async (_candidate: IceCandidateData) => undefined)
  close() {
    this.closed = true
  }

  // test helpers
  findCandidate(candidate: string) {
    this.onicecandidate?.({ candidate: { candidate, sdpMid: '0', sdpMLineIndex: 0 } })
  }
  changeState(state: string) {
    this.connectionState = state
    this.onconnectionstatechange?.()
  }
}

function fakeTrack(kind: 'audio' | 'video') {
  return { kind, enabled: true, stop: vi.fn() } as unknown as MediaStreamTrack & { stop: ReturnType<typeof vi.fn> }
}

function fakeStream(video = true) {
  const audio = fakeTrack('audio')
  const camera = fakeTrack('video')
  const tracks = video ? [audio, camera] : [audio]
  return {
    audio,
    camera,
    stream: {
      getTracks: () => tracks,
      getAudioTracks: () => [audio],
      getVideoTracks: () => (video ? [camera] : []),
    } as unknown as MediaStream,
  }
}

function apiError(status: number, errorCode?: string) {
  const headers = new AxiosHeaders()
  return new AxiosError('failed', 'ERR', undefined, undefined, {
    status,
    statusText: '',
    headers,
    config: { headers },
    data: { success: false, message: 'no', data: errorCode ? { errorCode } : null, timestamp: '' },
  })
}

function setUp() {
  const pcs: FakePeerConnection[] = []
  const media = fakeStream()
  let remembered: string | null = null
  const deps = {
    api: {
      startCall: vi.fn(async (): Promise<CallSession> => makeSession()),
      acceptCall: vi.fn(async (): Promise<CallSession> => makeSession({ direction: 'INCOMING', status: 'ACCEPTED' })),
      rejectCall: vi.fn(async (): Promise<Call> => makeCall({ status: 'REJECTED' })),
      endCall: vi.fn(async (): Promise<Call> => makeCall({ status: 'ENDED' })),
      sendSignal: vi.fn(async () => undefined),
      pingCall: vi.fn(async () => undefined),
      getActiveCall: vi.fn(async (): Promise<Call | null> => null),
      endCallKeepalive: vi.fn(),
    },
    getUserMedia: vi.fn(async () => media.stream),
    createPeerConnection: vi.fn((config: RTCConfiguration) => {
      const pc = new FakePeerConnection(config)
      pcs.push(pc)
      return pc
    }),
    notifyIncoming: vi.fn(),
    ringtone: { start: vi.fn(), stop: vi.fn() },
    relayOnly: vi.fn(() => false),
    rememberCall: vi.fn((id: string | null) => {
      remembered = id
    }),
    rememberedCall: vi.fn(() => remembered),
  } satisfies CallEngineDeps
  const engine = new CallEngine(deps)
  return { engine, deps, pcs, media, remember: (id: string | null) => (remembered = id) }
}

const ravi = profile(OTHER, 'Ravi Kumar')
const store = () => useCallStore.getState()
const settle = () => vi.advanceTimersByTimeAsync(0)

beforeEach(() => {
  vi.useFakeTimers()
  useCallStore.getState().reset()
})

afterEach(() => {
  vi.useRealTimers()
})

// ---- the caller ----

describe('CallEngine: making a call', () => {
  it('asks for camera and microphone BEFORE calling, then rings with the server iceServers', async () => {
    const { engine, deps, pcs } = setUp()

    await engine.startCall(ravi, 'VIDEO')

    expect(deps.getUserMedia).toHaveBeenCalledWith({ audio: true, video: true })
    expect(deps.getUserMedia.mock.invocationCallOrder[0]).toBeLessThan(deps.api.startCall.mock.invocationCallOrder[0])
    expect(deps.api.startCall).toHaveBeenCalledWith(OTHER, 'VIDEO')
    expect(store().state).toBe('outgoing')
    expect(store().call?.id).toBe('call-1')
    expect(deps.rememberCall).toHaveBeenCalledWith('call-1')
    expect(pcs).toHaveLength(1)
    expect(pcs[0].config.iceServers).toEqual(ICE_SERVERS)
    expect(pcs[0].config.iceTransportPolicy).toBeUndefined()
    expect(pcs[0].tracks).toHaveLength(2) // microphone + camera
    expect(pcs[0].createOffer).not.toHaveBeenCalled() // only after the other side accepted
  })

  it('a voice call asks only for the microphone', async () => {
    const { engine, deps } = setUp()
    await engine.startCall(ravi, 'AUDIO')
    expect(deps.getUserMedia).toHaveBeenCalledWith({ audio: true, video: false })
  })

  it('after CALL_ACCEPTED makes the offer, applies the answer, keeps early ICE candidates and becomes active', async () => {
    const { engine, deps, pcs } = setUp()
    await engine.startCall(ravi, 'VIDEO')
    const pc = pcs[0]

    engine.handleEvent(callEvent('CALL_ACCEPTED', { call: makeCall({ status: 'ACCEPTED', answeredAt: '2026-11-04T10:00:05Z' }), calleeDeviceId: 'd2' }))
    await settle()

    expect(store().state).toBe('connecting')
    expect(pc.createOffer).toHaveBeenCalled()
    expect(pc.setLocalDescription).toHaveBeenCalledWith({ type: 'offer', sdp: 'offer-sdp' })
    expect(deps.api.sendSignal).toHaveBeenCalledWith('call-1', { type: 'OFFER', sdp: 'offer-sdp' })

    // a candidate that arrives before the answer is kept ...
    const early = { candidate: 'candidate:1 1 udp 1 10.0.0.1 5000 typ host', sdpMid: '0', sdpMLineIndex: 0 }
    engine.handleEvent(callEvent('CALL_SIGNAL', { callId: 'call-1', fromUserId: OTHER, toDeviceId: 'd1', signalType: 'ICE', sdp: null, candidates: [early] }))
    await settle()
    expect(pc.addIceCandidate).not.toHaveBeenCalled()

    // ... and added right after the answer
    engine.handleEvent(callEvent('CALL_SIGNAL', { callId: 'call-1', fromUserId: OTHER, toDeviceId: 'd1', signalType: 'ANSWER', sdp: 'answer-sdp', candidates: null }))
    await settle()
    expect(pc.setRemoteDescription).toHaveBeenCalledWith({ type: 'answer', sdp: 'answer-sdp' })
    expect(pc.addIceCandidate).toHaveBeenCalledWith(early)

    pc.changeState('connected')
    expect(store().state).toBe('active')
    expect(store().connectionState).toBe('connected')
  })

  it('sends its own ICE candidates in one batch per 100 ms', async () => {
    const { engine, deps, pcs } = setUp()
    await engine.startCall(ravi, 'VIDEO')
    engine.handleEvent(callEvent('CALL_ACCEPTED', { call: makeCall({ status: 'ACCEPTED' }), calleeDeviceId: 'd2' }))
    await settle()
    deps.api.sendSignal.mockClear()

    pcs[0].findCandidate('c1')
    pcs[0].findCandidate('c2')
    pcs[0].findCandidate('c3')
    pcs[0].onicecandidate?.({ candidate: null }) // "no more" is not sent
    expect(deps.api.sendSignal).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(100)
    expect(deps.api.sendSignal).toHaveBeenCalledTimes(1)
    expect(deps.api.sendSignal).toHaveBeenCalledWith('call-1', {
      type: 'ICE',
      candidates: ['c1', 'c2', 'c3'].map((candidate) => ({ candidate, sdpMid: '0', sdpMLineIndex: 0 })),
    })
  })

  it('pings every 10 seconds while the call is accepted, and stops when it ends', async () => {
    const { engine, deps } = setUp()
    await engine.startCall(ravi, 'VIDEO')
    await vi.advanceTimersByTimeAsync(15_000)
    expect(deps.api.pingCall).not.toHaveBeenCalled() // still ringing

    engine.handleEvent(callEvent('CALL_ACCEPTED', { call: makeCall({ status: 'ACCEPTED' }), calleeDeviceId: 'd2' }))
    await vi.advanceTimersByTimeAsync(20_000)
    expect(deps.api.pingCall).toHaveBeenCalledTimes(2)
    expect(deps.api.pingCall).toHaveBeenCalledWith('call-1')

    engine.handleEvent(callEvent('CALL_ENDED', makeCall({ status: 'ENDED', endReason: 'HANGUP' })))
    await vi.advanceTimersByTimeAsync(30_000)
    expect(deps.api.pingCall).toHaveBeenCalledTimes(2)
  })

  it('uses only the TURN server when the debug switch says relay', async () => {
    const { engine, deps, pcs } = setUp()
    deps.relayOnly.mockReturnValue(true)
    await engine.startCall(ravi, 'VIDEO')
    expect(pcs[0].config.iceTransportPolicy).toBe('relay')
  })

  it('a refused microphone sends nothing to the server and explains why', async () => {
    const { engine, deps } = setUp()
    deps.getUserMedia.mockRejectedValue({ name: 'NotAllowedError' })

    await engine.startCall(ravi, 'VIDEO')

    expect(deps.api.startCall).not.toHaveBeenCalled()
    expect(store().state).toBe('ended')
    expect(store().endedText).toBe('Allow access to your camera and microphone in the browser to make calls.')
  })

  it('a busy callee: "is on another call", nothing rings, media stopped', async () => {
    const { engine, deps, pcs, media } = setUp()
    deps.api.startCall.mockResolvedValue({ call: makeCall({ status: 'BUSY', endReason: 'BUSY' }), iceServers: [] })

    await engine.startCall(ravi, 'VIDEO')

    expect(store().state).toBe('ended')
    expect(store().endedText).toBe('Ravi Kumar is on another call')
    expect(pcs).toHaveLength(0)
    expect(media.audio.stop).toHaveBeenCalled()
  })

  it('explains ALREADY_IN_CALL and CALL_NOT_ALLOWED', async () => {
    const { engine, deps } = setUp()
    deps.api.startCall.mockRejectedValueOnce(apiError(409, 'ALREADY_IN_CALL'))
    await engine.startCall(ravi, 'AUDIO')
    expect(store().endedText).toBe("You're already in a call.")

    engine.dismiss()
    deps.api.startCall.mockRejectedValueOnce(apiError(403, 'CALL_NOT_ALLOWED'))
    await engine.startCall(ravi, 'AUDIO')
    expect(store().endedText).toBe("You can't call Ravi Kumar.")
  })

  it('cancelling while it rings ends the call on the server and stops the camera', async () => {
    const { engine, deps, pcs, media } = setUp()
    await engine.startCall(ravi, 'VIDEO')

    engine.hangUp()

    expect(deps.api.endCall).toHaveBeenCalledWith('call-1')
    expect(store().state).toBe('ended')
    expect(store().endedText).toBe('Call cancelled')
    expect(pcs[0].closed).toBe(true)
    expect(media.camera.stop).toHaveBeenCalled()
    expect(deps.rememberCall).toHaveBeenLastCalledWith(null)

    await vi.advanceTimersByTimeAsync(3000)
    expect(store().state).toBe('idle') // the end screen closes itself
  })

  it('cancelling while the start request is still running ends the call when it arrives', async () => {
    const { engine, deps } = setUp()
    let answer: (session: CallSession) => void = () => undefined
    deps.api.startCall.mockReturnValue(new Promise((resolve) => (answer = resolve)))

    const starting = engine.startCall(ravi, 'VIDEO')
    await settle()
    engine.hangUp()
    answer(makeSession())
    await starting

    expect(deps.api.endCall).toHaveBeenCalledWith('call-1')
    expect(store().endedText).toBe('Call cancelled')
  })

  it('keeps events that arrive before its own start request answered', async () => {
    const { engine, deps } = setUp()
    let answer: (session: CallSession) => void = () => undefined
    deps.api.startCall.mockReturnValue(new Promise((resolve) => (answer = resolve)))

    const starting = engine.startCall(ravi, 'VIDEO')
    await settle()
    engine.handleEvent(callEvent('CALL_ACCEPTED', { call: makeCall({ status: 'ACCEPTED' }), calleeDeviceId: 'd2' }))
    answer(makeSession())
    await starting
    await settle()

    expect(store().state).toBe('connecting')
    expect(deps.api.sendSignal).toHaveBeenCalledWith('call-1', { type: 'OFFER', sdp: 'offer-sdp' })
  })

  it.each([
    ['NO_ANSWER', 'MISSED', 'No answer'],
    ['DECLINED', 'REJECTED', 'Declined'],
    ['HANGUP', 'ENDED', 'Call ended'],
    ['CONNECTION_LOST', 'ENDED', 'Connection lost'],
  ] as const)('CALL_ENDED %s shows "%s"', async (endReason, status, text) => {
    const { engine } = setUp()
    await engine.startCall(ravi, 'VIDEO')

    engine.handleEvent(callEvent('CALL_ENDED', makeCall({ status, endReason })))

    expect(store().state).toBe('ended')
    expect(store().endedText).toBe(text)
  })

  it('ignores events of calls this tab does not handle', async () => {
    const { engine, deps } = setUp()
    await engine.startCall(ravi, 'VIDEO')

    engine.handleEvent(callEvent('CALL_ENDED', makeCall({ id: 'other-call', status: 'ENDED', endReason: 'HANGUP' })))
    engine.handleEvent(callEvent('CALL_SIGNAL', { callId: 'other-call', fromUserId: OTHER, toDeviceId: 'x', signalType: 'OFFER', sdp: 'x', candidates: null }))
    engine.handleEvent({ nonsense: true })
    await settle()

    expect(store().state).toBe('outgoing')
    expect(deps.api.sendSignal).not.toHaveBeenCalled()
  })
})

// ---- the callee ----

describe('CallEngine: receiving a call', () => {
  const incoming = makeCall({ direction: 'INCOMING' })

  it('rings, shows a desktop notification, then answers: media first, accept, answer on the offer', async () => {
    const { engine, deps, pcs } = setUp()

    engine.handleEvent(callEvent('CALL_INCOMING', incoming))
    expect(store().state).toBe('incoming')
    expect(store().peer?.displayName).toBe('Ravi Kumar')
    expect(deps.ringtone.start).toHaveBeenCalled()
    expect(deps.notifyIncoming).toHaveBeenCalledWith(incoming)

    await engine.acceptIncoming()
    expect(deps.getUserMedia.mock.invocationCallOrder[0]).toBeLessThan(deps.api.acceptCall.mock.invocationCallOrder[0])
    expect(deps.api.acceptCall).toHaveBeenCalledWith('call-1')
    expect(deps.ringtone.stop).toHaveBeenCalled()
    expect(deps.rememberCall).toHaveBeenCalledWith('call-1')
    expect(store().state).toBe('connecting')
    const pc = pcs[0]

    // my own CALL_ACCEPTED does not end anything
    engine.handleEvent(callEvent('CALL_ACCEPTED', { call: makeCall({ direction: 'INCOMING', status: 'ACCEPTED' }), calleeDeviceId: 'me' }))
    expect(store().state).toBe('connecting')

    engine.handleEvent(callEvent('CALL_SIGNAL', { callId: 'call-1', fromUserId: OTHER, toDeviceId: 'me', signalType: 'OFFER', sdp: 'offer-sdp', candidates: null }))
    await settle()
    expect(pc.setRemoteDescription).toHaveBeenCalledWith({ type: 'offer', sdp: 'offer-sdp' })
    expect(pc.createAnswer).toHaveBeenCalled()
    expect(deps.api.sendSignal).toHaveBeenCalledWith('call-1', { type: 'ANSWER', sdp: 'answer-sdp' })

    await vi.advanceTimersByTimeAsync(10_000)
    expect(deps.api.pingCall).toHaveBeenCalledWith('call-1')
  })

  it('keeps an offer that arrives before the accept answer', async () => {
    const { engine, deps, pcs } = setUp()
    let answer: (session: CallSession) => void = () => undefined
    deps.api.acceptCall.mockReturnValue(new Promise((resolve) => (answer = resolve)))
    engine.handleEvent(callEvent('CALL_INCOMING', incoming))

    const accepting = engine.acceptIncoming()
    await settle()
    engine.handleEvent(callEvent('CALL_SIGNAL', { callId: 'call-1', fromUserId: OTHER, toDeviceId: 'me', signalType: 'OFFER', sdp: 'offer-sdp', candidates: null }))
    answer(makeSession({ direction: 'INCOMING', status: 'ACCEPTED' }))
    await accepting
    await settle()

    expect(pcs[0].setRemoteDescription).toHaveBeenCalledWith({ type: 'offer', sdp: 'offer-sdp' })
    expect(deps.api.sendSignal).toHaveBeenCalledWith('call-1', { type: 'ANSWER', sdp: 'answer-sdp' })
  })

  it('a refused microphone keeps the call ringing, says why and sends nothing', async () => {
    const { engine, deps } = setUp()
    deps.getUserMedia.mockRejectedValue({ name: 'NotFoundError' })
    engine.handleEvent(callEvent('CALL_INCOMING', incoming))

    await engine.acceptIncoming()

    expect(deps.api.acceptCall).not.toHaveBeenCalled()
    expect(store().state).toBe('incoming')
    expect(store().error).toBe('No camera and microphone found on this device.')
  })

  it('stops ringing when another device of mine answered', () => {
    const { engine, deps } = setUp()
    engine.handleEvent(callEvent('CALL_INCOMING', incoming))

    engine.handleEvent(callEvent('CALL_ACCEPTED', { call: makeCall({ direction: 'INCOMING', status: 'ACCEPTED' }), calleeDeviceId: 'phone' }))

    expect(store().state).toBe('ended')
    expect(store().endedText).toBe('Answered on another device')
    expect(deps.ringtone.stop).toHaveBeenCalled()
  })

  it('decline closes the screen and tells the server', () => {
    const { engine, deps } = setUp()
    engine.handleEvent(callEvent('CALL_INCOMING', incoming))

    engine.declineIncoming()

    expect(deps.api.rejectCall).toHaveBeenCalledWith('call-1')
    expect(store().state).toBe('idle')
    expect(deps.ringtone.stop).toHaveBeenCalled()
  })

  it('the caller giving up turns the ringing into "Missed call"', () => {
    const { engine } = setUp()
    engine.handleEvent(callEvent('CALL_INCOMING', incoming))

    engine.handleEvent(callEvent('CALL_ENDED', makeCall({ direction: 'INCOMING', status: 'MISSED', endReason: 'CANCELLED' })))

    expect(store().endedText).toBe('Missed call')
  })

  it('a call that is no longer ringing when I accept shows why', async () => {
    const { engine, deps, media } = setUp()
    deps.api.acceptCall.mockRejectedValue(apiError(409, 'CALL_NOT_RINGING'))
    engine.handleEvent(callEvent('CALL_INCOMING', incoming))

    await engine.acceptIncoming()

    expect(store().endedText).toBe('This call is no longer available.')
    expect(media.audio.stop).toHaveBeenCalled()
  })
})

// ---- during the call ----

describe('CallEngine: during a call', () => {
  async function activeCall() {
    const setup = setUp()
    await setup.engine.startCall(ravi, 'VIDEO')
    setup.engine.handleEvent(callEvent('CALL_ACCEPTED', { call: makeCall({ status: 'ACCEPTED' }), calleeDeviceId: 'd2' }))
    await settle()
    setup.pcs[0].changeState('connected')
    return setup
  }

  it('mute and camera switch the tracks off and on', async () => {
    const { engine, media } = await activeCall()

    engine.toggleMute()
    expect(media.audio.enabled).toBe(false)
    expect(store().muted).toBe(true)
    engine.toggleMute()
    expect(media.audio.enabled).toBe(true)

    engine.toggleCamera()
    expect(media.camera.enabled).toBe(false)
    expect(store().cameraOff).toBe(true)
  })

  it('a failed connection ends the call', async () => {
    const { engine, deps, pcs } = await activeCall()
    pcs[0].changeState('failed')
    expect(store().endedText).toBe('Connection lost')
    expect(deps.api.endCall).toHaveBeenCalledWith('call-1')
    expect(engine).toBeDefined()
  })

  it('"disconnected" for 10 seconds ends the call, a quick recovery does not', async () => {
    const { deps, pcs } = await activeCall()

    pcs[0].changeState('disconnected')
    await vi.advanceTimersByTimeAsync(5000)
    pcs[0].changeState('connected')
    await vi.advanceTimersByTimeAsync(10_000)
    expect(store().state).toBe('active')

    pcs[0].changeState('disconnected')
    await vi.advanceTimersByTimeAsync(10_000)
    expect(store().endedText).toBe('Connection lost')
    expect(deps.api.endCall).toHaveBeenCalledWith('call-1')
  })

  it('hang up ends it on the server; closing the page ends it with keepalive', async () => {
    const first = await activeCall()
    first.engine.onPageHide()
    expect(first.deps.api.endCallKeepalive).toHaveBeenCalledWith('call-1')

    first.engine.hangUp()
    expect(first.deps.api.endCall).toHaveBeenCalledWith('call-1')
    expect(store().endedText).toBe('Call ended')
    expect(first.pcs[0].closed).toBe(true)
  })

  it('shows the other person\'s video once its video track arrives', async () => {
    const { pcs } = await activeCall()
    const remote = fakeStream()
    pcs[0].ontrack?.({ track: remote.audio, streams: [remote.stream] })
    expect(store().remoteStream).toBe(remote.stream)
    expect(store().remoteVideo).toBe(true)
  })
})

// ---- reconnect / reload ----

describe('CallEngine: which call am I in? (after a reconnect or reload)', () => {
  it('ends the call this tab had before a reload', async () => {
    const { engine, deps, remember } = setUp()
    remember('call-1')
    deps.api.getActiveCall.mockResolvedValue(makeCall({ status: 'ACCEPTED' }))

    await engine.syncActiveCall()

    expect(deps.api.endCall).toHaveBeenCalledWith('call-1')
    expect(store().state).toBe('idle')
  })

  it('shows a call that is ringing for me', async () => {
    const { engine, deps } = setUp()
    deps.api.getActiveCall.mockResolvedValue(makeCall({ direction: 'INCOMING' }))

    await engine.syncActiveCall()

    expect(store().state).toBe('incoming')
    expect(deps.api.endCall).not.toHaveBeenCalled()
  })

  it('leaves a call on another device of mine alone', async () => {
    const { engine, deps } = setUp()
    deps.api.getActiveCall.mockResolvedValue(makeCall({ status: 'ACCEPTED' }))

    await engine.syncActiveCall()

    expect(deps.api.endCall).not.toHaveBeenCalled()
    expect(store().state).toBe('idle')
  })

  it('stops ringing when the server no longer knows the call (an end event was missed)', async () => {
    const { engine } = setUp()
    engine.handleEvent(callEvent('CALL_INCOMING', makeCall({ direction: 'INCOMING' })))

    await engine.syncActiveCall()

    expect(store().state).toBe('idle')
  })

  it('catches up on a missed CALL_ACCEPTED of my outgoing call', async () => {
    const { engine, deps } = setUp()
    await engine.startCall(ravi, 'VIDEO')
    deps.api.getActiveCall.mockResolvedValue(makeCall({ status: 'ACCEPTED' }))

    await engine.syncActiveCall()
    await settle()

    expect(store().state).toBe('connecting')
    expect(deps.api.sendSignal).toHaveBeenCalledWith('call-1', expect.objectContaining({ type: 'OFFER' }))
  })

  it('logout ends a running call with keepalive and forgets everything', async () => {
    const { engine, deps } = setUp()
    await engine.startCall(ravi, 'VIDEO')

    engine.stop()

    expect(deps.api.endCallKeepalive).toHaveBeenCalledWith('call-1')
    expect(store().state).toBe('idle')
  })
})
