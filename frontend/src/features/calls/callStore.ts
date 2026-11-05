import { create } from 'zustand'
import type { Call, CallType } from '../../types/call'
import type { PublicProfile } from '../../types/user'

// What the call screens show. The engine (callEngine.ts) is the only code that changes it; components only read it
// and call the engine's methods (start, accept, hang up ...).
//
//   idle -> outgoing ("Calling...")  -> connecting -> active -> ended -> idle (after a few seconds)
//   idle -> incoming ("ringing")     -> connecting -> active -> ended -> idle
// Any state can jump to ended (declined, no answer, hung up, connection lost ...).
export type CallUiState = 'idle' | 'outgoing' | 'incoming' | 'connecting' | 'active' | 'ended'

interface CallStoreState {
  state: CallUiState
  // The call as the server last described it (null while an outgoing call is still being created).
  call: Call | null
  // Who we are calling / who calls us (known even before the server answered).
  peer: PublicProfile | null
  type: CallType
  // The RTCPeerConnection's connectionState: 'new' | 'connecting' | 'connected' | 'disconnected' | 'failed' | 'closed'
  connectionState: string
  localStream: MediaStream | null
  remoteStream: MediaStream | null
  // true once the other person's VIDEO track arrived (the same stream object grows track by track, so this flag is
  // what tells React to show the video)
  remoteVideo: boolean
  muted: boolean
  cameraOff: boolean
  // incoming: true while the browser asks for the microphone/camera before answering
  accepting: boolean
  // incoming: why answering did not work (for example the microphone was refused); the call keeps ringing
  error: string | null
  // ended: why the call is over ("No answer", "Declined", "Call ended" ...)
  endedText: string | null
  // Grows every time a call ends, so the Calls page can reload its list.
  historyVersion: number

  patch: (changes: Partial<Omit<CallStoreState, 'patch' | 'reset'>>) => void
  reset: () => void
}

const initialState = {
  state: 'idle' as CallUiState,
  call: null as Call | null,
  peer: null as PublicProfile | null,
  type: 'AUDIO' as CallType,
  connectionState: 'new',
  localStream: null as MediaStream | null,
  remoteStream: null as MediaStream | null,
  remoteVideo: false,
  muted: false,
  cameraOff: false,
  accepting: false,
  error: null as string | null,
  endedText: null as string | null,
}

export const useCallStore = create<CallStoreState>((set) => ({
  ...initialState,
  historyVersion: 0,
  patch: (changes) => set(changes),
  // Back to "no call" (keeps historyVersion so the Calls page does not reload for nothing).
  reset: () => set({ ...initialState }),
}))

// True while a call is starting, ringing or running (the call buttons are disabled then).
export function isBusyState(state: CallUiState): boolean {
  return state !== 'idle' && state !== 'ended'
}
