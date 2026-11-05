// The call engine: everything a one-to-one audio / video call needs in this browser tab.
//
// The pieces (API_DOCUMENTATION.md 7.3, WEBSOCKET_DESIGN.md 9):
// - The SERVER keeps the call's state (ringing, accepted, ended ...) and relays "signaling" messages. We send with REST
//   (services/api/callApi.ts) and receive live events on /user/queue/calls (callRealtime.ts passes them to handleEvent).
// - The AUDIO and VIDEO go straight from this browser to the other one (WebRTC: RTCPeerConnection). The server never
//   sees them. When a direct path is impossible, the TURN server (Coturn) relays them; its address and short-lived
//   password come in `iceServers`.
//
// How a call is set up:
//   caller: microphone/camera -> POST /api/calls ("ringing")
//   callee: CALL_INCOMING -> rings -> Accept -> microphone/camera -> POST accept
//   caller: CALL_ACCEPTED -> creates the OFFER (what it can send/receive) -> signal OFFER
//   callee: OFFER -> creates the ANSWER -> signal ANSWER
//   both:   find network addresses ("ICE candidates") and send them to each other in small batches
//   both:   the browsers connect -> "active" (timer runs); every 10 s each side pings the server
//
// The UI state lives in callStore.ts; components only read it and call the methods below.
import * as callApi from '../../services/api/callApi'
import type { Call, CallEvent, CallSession, CallSignalData, CallType, IceCandidateData, IceServerConfig } from '../../types/call'
import type { PublicProfile } from '../../types/user'
import { readStorage } from '../../utils/storage'
import { showIncomingCallNotification } from '../notifications/localNotifications'
import { callErrorText, endedText, mediaErrorText } from './callText'
import { isBusyState, useCallStore } from './callStore'
import { ringtone } from './ringtone'

// Debug switch (also used by the end-to-end test): localStorage['chatapp.iceTransportPolicy'] = 'relay' makes the
// browser use ONLY the TURN server. If a call still connects, Coturn and its credentials work.
export const RELAY_SWITCH_KEY = 'chatapp.iceTransportPolicy'
// The call this tab is handling, remembered across a page reload (see syncActiveCall).
export const CURRENT_CALL_KEY = 'chatapp.currentCallId'

const PING_INTERVAL_MS = 10_000 // the server ends a call when one side was silent for 30 s
const ICE_BATCH_MS = 100 // candidates found within 100 ms travel in one request
const MAX_CANDIDATES_PER_SIGNAL = 50 // the server accepts at most 50 per request
const DISCONNECTED_GRACE_MS = 10_000 // a short network hiccup ("disconnected") may heal by itself
const ENDED_VISIBLE_MS = 3_000 // how long "Call ended" stays on screen
const MAX_WAITING_ITEMS = 200 // safety limit for the small queues below

// The part of RTCPeerConnection we use (tests pass a fake).
export interface PeerConnectionLike {
  readonly connectionState: string
  readonly remoteDescription: unknown
  onicecandidate:
    | ((event: { candidate: { candidate: string; sdpMid?: string | null; sdpMLineIndex?: number | null } | null }) => void)
    | null
  ontrack: ((event: { track: MediaStreamTrack; streams: readonly MediaStream[] }) => void) | null
  onconnectionstatechange: (() => void) | null
  addTrack(track: MediaStreamTrack, stream: MediaStream): unknown
  createOffer(): Promise<{ type: string; sdp?: string }>
  createAnswer(): Promise<{ type: string; sdp?: string }>
  setLocalDescription(description: { type: string; sdp?: string }): Promise<void>
  setRemoteDescription(description: { type: 'offer' | 'answer'; sdp: string }): Promise<void>
  addIceCandidate(candidate: IceCandidateData): Promise<void>
  close(): void
}

// Everything the engine needs from the outside world (tests replace all of it).
export interface CallEngineDeps {
  api: Pick<
    typeof callApi,
    'startCall' | 'acceptCall' | 'rejectCall' | 'endCall' | 'sendSignal' | 'pingCall' | 'getActiveCall' | 'endCallKeepalive'
  >
  getUserMedia(constraints: MediaStreamConstraints): Promise<MediaStream>
  createPeerConnection(config: RTCConfiguration): PeerConnectionLike
  notifyIncoming(call: Call): void
  ringtone: { start(): void; stop(): void }
  relayOnly(): boolean
  rememberCall(id: string | null): void
  rememberedCall(): string | null
}

function noop() {
  // nothing to do
}

function stopTracks(stream: MediaStream | null): void {
  stream?.getTracks().forEach((track) => track.stop())
}

function mediaConstraints(type: CallType): MediaStreamConstraints {
  return { audio: true, video: type === 'VIDEO' }
}

export class CallEngine {
  private deps: CallEngineDeps

  // Grows every time a call starts, rings or ends. Async work (waiting for the microphone, a request) checks it
  // afterwards: a different number means "that call is over, do nothing".
  private generation = 0
  private callId: string | null = null
  private pc: PeerConnectionLike | null = null
  private localStream: MediaStream | null = null
  // incoming: true once THIS tab sent "accept" (CALL_ACCEPTED is then ours, not another device's)
  private acceptSent = false

  // Events that arrived while our own POST /api/calls was still running (we did not know the call id yet).
  private bufferedEvents: CallEvent[] = []
  // Signals that arrived before the peer connection existed (the accept answer was slower than the offer).
  private pendingSignals: CallSignalData[] = []
  // ICE candidates of the other side that arrived before its offer/answer: added once the description is set.
  private remoteCandidates: IceCandidateData[] = []
  // Our own candidates, waiting up to 100 ms to travel together.
  private outgoingCandidates: IceCandidateData[] = []
  // Signals are applied one after the other (an ICE candidate must wait until the offer/answer is in place).
  private signalChain: Promise<void> = Promise.resolve()

  private iceTimer: ReturnType<typeof setTimeout> | null = null
  private pingTimer: ReturnType<typeof setInterval> | null = null
  private disconnectTimer: ReturnType<typeof setTimeout> | null = null
  private closeTimer: ReturnType<typeof setTimeout> | null = null

  constructor(deps: CallEngineDeps) {
    this.deps = deps
  }

  private get store() {
    return useCallStore.getState()
  }

  // ------------------------------------------------------------------ actions of the user

  // "Voice call" / "Video call": camera and microphone FIRST (nothing is sent if the browser refuses), then ring.
  async startCall(peer: PublicProfile, type: CallType): Promise<void> {
    if (isBusyState(this.store.state)) return
    this.clearCloseTimer()
    const generation = ++this.generation
    this.store.reset()
    this.store.patch({ state: 'outgoing', peer, type })

    let stream: MediaStream
    try {
      stream = await this.deps.getUserMedia(mediaConstraints(type))
    } catch (error) {
      if (generation === this.generation) this.finish(mediaErrorText(error, type))
      return
    }
    if (generation !== this.generation) {
      stopTracks(stream) // cancelled while the browser was asking
      return
    }
    this.useLocalStream(stream)

    let session: CallSession
    try {
      session = await this.deps.api.startCall(peer.id, type)
    } catch (error) {
      if (generation === this.generation) this.finish(callErrorText(error, peer.displayName))
      return
    }
    if (generation !== this.generation) {
      // Cancelled while the request was running: the call exists on the server now, so end it there too.
      this.deps.api.endCall(session.call.id).catch(noop)
      return
    }
    if (session.call.status === 'BUSY') {
      this.finish(endedText(session.call), session.call) // "<name> is on another call": nothing rings
      return
    }

    this.callId = session.call.id
    this.deps.rememberCall(session.call.id)
    this.store.patch({ call: session.call })
    this.createPeerConnection(session.iceServers)

    const waiting = this.bufferedEvents
    this.bufferedEvents = []
    waiting.forEach((event) => this.handleEvent(event))
  }

  // "Accept": microphone/camera first (if refused the call keeps ringing and says why), then tell the server.
  async acceptIncoming(): Promise<void> {
    const { state, call, accepting } = this.store
    if (state !== 'incoming' || !call || accepting) return
    const generation = this.generation
    this.store.patch({ accepting: true, error: null })

    let stream: MediaStream
    try {
      stream = await this.deps.getUserMedia(mediaConstraints(call.type))
    } catch (error) {
      if (generation === this.generation) this.store.patch({ accepting: false, error: mediaErrorText(error, call.type) })
      return
    }
    if (generation !== this.generation) {
      stopTracks(stream) // the call ended (or was answered elsewhere) while the browser was asking
      return
    }

    this.deps.ringtone.stop()
    this.useLocalStream(stream)
    this.acceptSent = true
    this.store.patch({ state: 'connecting', accepting: false })

    let session: CallSession
    try {
      session = await this.deps.api.acceptCall(call.id)
    } catch (error) {
      if (generation === this.generation) this.finish(callErrorText(error))
      return
    }
    if (generation !== this.generation) return // ended meanwhile: CALL_ENDED already cleaned up

    this.deps.rememberCall(call.id)
    this.store.patch({ call: session.call })
    this.createPeerConnection(session.iceServers)
    this.startPing()

    const waiting = this.pendingSignals
    this.pendingSignals = []
    waiting.forEach((signal) => this.onSignal(signal))
  }

  // "Decline": the screen closes at once; the caller sees "Declined".
  declineIncoming(): void {
    const { state, call } = this.store
    if (state !== 'incoming' || !call) return
    this.finish(null)
    this.deps.api.rejectCall(call.id).catch(noop)
  }

  // "Hang up" / "Cancel call" (or "Decline" while ringing).
  hangUp(): void {
    const { state } = this.store
    if (state === 'incoming') {
      this.declineIncoming()
      return
    }
    if (!isBusyState(state)) return
    const id = this.callId
    this.finish(state === 'outgoing' ? 'Call cancelled' : 'Call ended')
    // (If POST /api/calls is still running, startCall ends the new call when the answer arrives.)
    if (id) this.deps.api.endCall(id).catch(noop)
  }

  toggleMute(): void {
    if (!this.localStream) return
    const muted = !this.store.muted
    this.localStream.getAudioTracks().forEach((track) => (track.enabled = !muted))
    this.store.patch({ muted })
  }

  toggleCamera(): void {
    if (!this.localStream) return
    const cameraOff = !this.store.cameraOff
    this.localStream.getVideoTracks().forEach((track) => (track.enabled = !cameraOff))
    this.store.patch({ cameraOff })
  }

  // Close the "Call ended" screen before it closes itself.
  dismiss(): void {
    if (this.store.state !== 'ended') return
    this.clearCloseTimer()
    this.store.reset()
  }

  // ------------------------------------------------------------------ live events (/user/queue/calls)

  handleEvent(raw: unknown): void {
    if (!raw || typeof raw !== 'object' || !('type' in raw) || !('data' in raw) || !raw.data) return
    const event = raw as CallEvent

    // Our own POST /api/calls has not answered yet, so we cannot tell whether an event is about our call: keep it.
    if (this.store.state === 'outgoing' && this.callId === null && event.type !== 'CALL_INCOMING') {
      if (this.bufferedEvents.length < MAX_WAITING_ITEMS) this.bufferedEvents.push(event)
      return
    }

    switch (event.type) {
      case 'CALL_INCOMING':
        this.onIncoming(event.data)
        break
      case 'CALL_ACCEPTED':
        this.onAccepted(event.data.call)
        break
      case 'CALL_ENDED':
        this.onEnded(event.data)
        break
      case 'CALL_SIGNAL':
        this.onSignal(event.data)
        break
    }
  }

  private onIncoming(call: Call): void {
    if (call.direction !== 'INCOMING' || call.status !== 'RINGING') return
    if (call.id === this.callId) return // already ringing for it
    if (isBusyState(this.store.state)) return // this tab is busy (the server normally answers BUSY by itself)
    this.startRinging(call)
  }

  private startRinging(call: Call): void {
    this.clearCloseTimer()
    this.generation++
    this.store.reset()
    this.store.patch({ state: 'incoming', call, peer: call.otherUser, type: call.type })
    this.callId = call.id
    this.deps.ringtone.start()
    this.deps.notifyIncoming(call)
  }

  private onAccepted(call: Call): void {
    if (call.id !== this.callId) return // not a call this tab handles
    if (call.direction === 'OUTGOING') {
      // I am the caller: the other side answered. Only this tab (the one that started the call) makes the offer.
      if (this.store.state !== 'outgoing') return
      this.store.patch({ state: 'connecting', call })
      this.startPing()
      this.enqueue(() => this.makeOffer())
      return
    }
    if (this.acceptSent) {
      this.store.patch({ call }) // my own accept, confirmed
      return
    }
    // I was only ringing here: another device (or another tab) took the call.
    this.finish('Answered on another device', call)
  }

  private onEnded(call: Call): void {
    if (call.id !== this.callId) return
    this.finish(endedText(call), call)
  }

  private onSignal(signal: CallSignalData): void {
    if (signal.callId !== this.callId) return
    if (!this.pc) {
      if (this.pendingSignals.length < MAX_WAITING_ITEMS) this.pendingSignals.push(signal)
      return
    }
    this.enqueue(() => this.applySignal(signal))
  }

  // ------------------------------------------------------------------ after a reconnect / reload

  // Asks the server which call I am in. Called on app start and after every (re)connect of the socket, because live
  // events may have been missed while the connection was down (or the page was reloaded).
  async syncActiveCall(): Promise<void> {
    let active: Call | null
    try {
      active = await this.deps.api.getActiveCall()
    } catch {
      return // offline: the next reconnect asks again
    }
    const { state } = this.store

    if (active === null) {
      // The server knows no call: an end event was missed.
      if (this.callId && state === 'incoming') this.finish(null)
      else if (this.callId && isBusyState(state)) this.finish('Call ended')
      return
    }

    if (active.id === this.callId) {
      // Still the same call: catch up on a missed CALL_ACCEPTED.
      if (active.status === 'ACCEPTED') this.onAccepted(active)
      return
    }

    // A call this tab is not handling right now.
    if (this.deps.rememberedCall() === active.id) {
      // This tab had it before a reload: the old browser-to-browser connection is gone, so end it.
      this.deps.rememberCall(null)
      this.deps.api.endCall(active.id).catch(noop)
      return
    }
    if (active.status === 'RINGING' && active.direction === 'INCOMING' && !isBusyState(state)) {
      this.startRinging(active) // somebody is calling me right now
    }
    // Otherwise it is a call on another device of mine: nothing to show here.
  }

  // The page is closing (reload, tab closed): hang up a call this tab is in. Fetch with keepalive survives the unload.
  onPageHide(): void {
    const { state } = this.store
    if (this.callId && (state === 'outgoing' || state === 'connecting' || state === 'active')) {
      this.deps.api.endCallKeepalive(this.callId)
    }
  }

  // Logout / the app closes: end a running call and forget everything.
  stop(): void {
    const { state } = this.store
    if (this.callId && isBusyState(state) && state !== 'incoming') this.deps.api.endCallKeepalive(this.callId)
    this.finish(null)
    this.store.reset()
  }

  // ------------------------------------------------------------------ WebRTC

  private useLocalStream(stream: MediaStream): void {
    this.localStream = stream
    this.store.patch({ localStream: stream })
  }

  private createPeerConnection(iceServers: IceServerConfig[]): void {
    const config: RTCConfiguration = {
      iceServers: iceServers.map((server) => ({ urls: server.urls, username: server.username, credential: server.credential })),
    }
    if (this.deps.relayOnly()) config.iceTransportPolicy = 'relay'

    const pc = this.deps.createPeerConnection(config)
    this.pc = pc
    const generation = this.generation

    pc.onicecandidate = (event) => {
      if (generation !== this.generation || !event.candidate) return // null = "no more candidates"
      this.queueCandidate({
        candidate: event.candidate.candidate,
        sdpMid: event.candidate.sdpMid ?? null,
        sdpMLineIndex: event.candidate.sdpMLineIndex ?? null,
      })
    }
    pc.ontrack = (event) => {
      if (generation !== this.generation) return
      const stream = event.streams[0] ?? (typeof MediaStream !== 'undefined' ? new MediaStream([event.track]) : null)
      if (stream) this.store.patch({ remoteStream: stream, remoteVideo: stream.getVideoTracks().length > 0 })
    }
    pc.onconnectionstatechange = () => {
      if (generation === this.generation) this.onConnectionState(pc.connectionState)
    }

    const stream = this.localStream
    stream?.getTracks().forEach((track) => pc.addTrack(track, stream))
  }

  private onConnectionState(connectionState: string): void {
    this.store.patch({ connectionState })
    if (connectionState === 'connected') {
      this.clearDisconnectTimer()
      if (this.store.state === 'connecting') this.store.patch({ state: 'active' })
    } else if (connectionState === 'disconnected') {
      // Often heals by itself (Wi-Fi switch). Give it 10 seconds.
      if (!this.disconnectTimer) {
        this.disconnectTimer = setTimeout(() => {
          this.disconnectTimer = null
          this.connectionLost()
        }, DISCONNECTED_GRACE_MS)
      }
    } else if (connectionState === 'failed') {
      this.connectionLost()
    }
  }

  private connectionLost(): void {
    const id = this.callId
    this.finish('Connection lost')
    if (id) this.deps.api.endCall(id).catch(noop)
  }

  // Runs signal work one step at a time. A failure (the browser refused a description) ends the call.
  private enqueue(task: () => Promise<void>): void {
    const generation = this.generation
    this.signalChain = this.signalChain.then(async () => {
      if (generation !== this.generation) return
      try {
        await task()
      } catch {
        if (generation === this.generation) this.connectionLost()
      }
    })
  }

  // Caller: "here is what I can send and receive".
  private async makeOffer(): Promise<void> {
    const pc = this.pc
    const id = this.callId
    if (!pc || !id) return
    const offer = await pc.createOffer()
    await pc.setLocalDescription(offer)
    await this.deps.api.sendSignal(id, { type: 'OFFER', sdp: offer.sdp ?? '' })
  }

  private async applySignal(signal: CallSignalData): Promise<void> {
    const pc = this.pc
    if (!pc) return
    switch (signal.signalType) {
      case 'OFFER': {
        // Callee: take the caller's offer and answer it.
        if (!signal.sdp) return
        await pc.setRemoteDescription({ type: 'offer', sdp: signal.sdp })
        await this.addWaitingCandidates(pc)
        const answer = await pc.createAnswer()
        await pc.setLocalDescription(answer)
        await this.deps.api.sendSignal(signal.callId, { type: 'ANSWER', sdp: answer.sdp ?? '' })
        break
      }
      case 'ANSWER':
        if (!signal.sdp) return
        await pc.setRemoteDescription({ type: 'answer', sdp: signal.sdp })
        await this.addWaitingCandidates(pc)
        break
      case 'ICE':
        for (const candidate of signal.candidates ?? []) {
          if (pc.remoteDescription) {
            await pc.addIceCandidate(candidate).catch(noop) // one bad candidate must not end the call
          } else if (this.remoteCandidates.length < MAX_WAITING_ITEMS) {
            this.remoteCandidates.push(candidate) // too early: the offer/answer is not there yet
          }
        }
        break
    }
  }

  private async addWaitingCandidates(pc: PeerConnectionLike): Promise<void> {
    const waiting = this.remoteCandidates
    this.remoteCandidates = []
    for (const candidate of waiting) {
      await pc.addIceCandidate(candidate).catch(noop)
    }
  }

  private queueCandidate(candidate: IceCandidateData): void {
    this.outgoingCandidates.push(candidate)
    if (this.outgoingCandidates.length >= MAX_CANDIDATES_PER_SIGNAL) {
      this.flushCandidates()
    } else if (!this.iceTimer) {
      this.iceTimer = setTimeout(() => this.flushCandidates(), ICE_BATCH_MS)
    }
  }

  private flushCandidates(): void {
    if (this.iceTimer) clearTimeout(this.iceTimer)
    this.iceTimer = null
    const id = this.callId
    while (id && this.outgoingCandidates.length > 0) {
      const batch = this.outgoingCandidates.splice(0, MAX_CANDIDATES_PER_SIGNAL)
      this.deps.api.sendSignal(id, { type: 'ICE', candidates: batch }).catch(noop) // the next batches may still work
    }
    this.outgoingCandidates = []
  }

  private startPing(): void {
    const id = this.callId
    if (this.pingTimer || !id) return
    this.pingTimer = setInterval(() => {
      this.deps.api.pingCall(id).catch(noop) // a lost ping is fine: the next one comes in 10 s
    }, PING_INTERVAL_MS)
  }

  // ------------------------------------------------------------------ the end of every call

  // Stops the microphone/camera, closes the connection, clears every timer. text = what the screen shows for a few
  // seconds ("No answer" ...), or null to close the call screen at once.
  private finish(text: string | null, call?: Call): void {
    this.generation++
    this.clearTimers()
    this.deps.ringtone.stop()

    const pc = this.pc
    if (pc) {
      pc.onicecandidate = null
      pc.ontrack = null
      pc.onconnectionstatechange = null
      try {
        pc.close()
      } catch {
        // already closed
      }
    }
    stopTracks(this.localStream)

    this.pc = null
    this.localStream = null
    this.callId = null
    this.acceptSent = false
    this.bufferedEvents = []
    this.pendingSignals = []
    this.remoteCandidates = []
    this.outgoingCandidates = []
    this.signalChain = Promise.resolve()
    this.deps.rememberCall(null)

    const store = this.store
    const historyVersion = store.historyVersion + 1
    if (text === null) {
      store.reset()
      store.patch({ historyVersion })
      return
    }
    store.patch({
      state: 'ended',
      endedText: text,
      call: call ?? store.call,
      localStream: null,
      remoteStream: null,
      remoteVideo: false,
      accepting: false,
      error: null,
      historyVersion,
    })
    const generation = this.generation
    this.closeTimer = setTimeout(() => {
      this.closeTimer = null
      if (generation === this.generation && this.store.state === 'ended') this.store.reset()
    }, ENDED_VISIBLE_MS)
  }

  private clearTimers(): void {
    if (this.iceTimer) clearTimeout(this.iceTimer)
    if (this.pingTimer) clearInterval(this.pingTimer)
    this.iceTimer = null
    this.pingTimer = null
    this.clearDisconnectTimer()
    this.clearCloseTimer()
  }

  private clearDisconnectTimer(): void {
    if (this.disconnectTimer) clearTimeout(this.disconnectTimer)
    this.disconnectTimer = null
  }

  private clearCloseTimer(): void {
    if (this.closeTimer) clearTimeout(this.closeTimer)
    this.closeTimer = null
  }
}

// ---- the real dependencies ----

function rememberInSession(id: string | null): void {
  try {
    if (id) sessionStorage.setItem(CURRENT_CALL_KEY, id)
    else sessionStorage.removeItem(CURRENT_CALL_KEY)
  } catch {
    // storage blocked: a reload then simply waits for the server's 30-second timeout
  }
}

function rememberedInSession(): string | null {
  try {
    return sessionStorage.getItem(CURRENT_CALL_KEY)
  } catch {
    return null
  }
}

export const browserDeps: CallEngineDeps = {
  api: callApi,
  getUserMedia: (constraints) => {
    if (typeof navigator === 'undefined' || !navigator.mediaDevices?.getUserMedia) {
      // Only secure pages (https or localhost) may use the camera and microphone.
      return Promise.reject({ name: 'NoMediaDevices' })
    }
    return navigator.mediaDevices.getUserMedia(constraints)
  },
  createPeerConnection: (config) => new RTCPeerConnection(config) as unknown as PeerConnectionLike,
  notifyIncoming: (call) => {
    showIncomingCallNotification(call)
  },
  ringtone,
  relayOnly: () => readStorage(RELAY_SWITCH_KEY) === 'relay',
  rememberCall: rememberInSession,
  rememberedCall: rememberedInSession,
}

// The ONE engine of the app.
export const callEngine = new CallEngine(browserDeps)
