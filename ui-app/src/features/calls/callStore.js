import { create } from 'zustand'

// What the call screens show. The engine (callEngine.js) is the only code that changes it; components only read it
// and call the engine's methods (start, accept, hang up ...).
//
//   idle -> outgoing ("Calling...")  -> connecting -> active -> ended -> idle (after a few seconds)
//   idle -> incoming ("ringing")     -> connecting -> active -> ended -> idle
// Any state can jump to ended (declined, no answer, hung up, connection lost ...).
//
// State:
//   state            'idle' | 'outgoing' | 'incoming' | 'connecting' | 'active' | 'ended'
//   call             the call as the server last described it (null while an outgoing call is still being created)
//   peer             who we are calling / who calls us (known even before the server answered)
//   type             'AUDIO' | 'VIDEO'
//   connectionState  the RTCPeerConnection's connectionState: 'new' | 'connecting' | 'connected' | 'disconnected' | 'failed' | 'closed'
//   localStream      my microphone / camera (MediaStream)
//   remoteStream     the other person's sound / picture (MediaStream)
//   remoteVideo      true once the other person's VIDEO track arrived (the same stream object grows track by track,
//                    so this flag is what tells React to show the video)
//   muted, cameraOff my switches
//   accepting        incoming: true while the browser asks for the microphone/camera before answering
//   error            incoming: why answering did not work (for example the microphone was refused); the call keeps ringing
//   endedText        ended: why the call is over ("No answer", "Declined", "Call ended" ...)
//   historyVersion   grows every time a call ends, so the Calls page can reload its list

const initialState = {
  state: 'idle',
  call: null,
  peer: null,
  type: 'AUDIO',
  connectionState: 'new',
  localStream: null,
  remoteStream: null,
  remoteVideo: false,
  muted: false,
  cameraOff: false,
  accepting: false,
  error: null,
  endedText: null,
}

export const useCallStore = create((set) => ({
  ...initialState,
  historyVersion: 0,
  patch: (changes) => set(changes),
  // Back to "no call" (keeps historyVersion so the Calls page does not reload for nothing).
  reset: () => set({ ...initialState }),
}))

// True while a call is starting, ringing or running (the call buttons are disabled then).
export function isBusyState(state) {
  return state !== 'idle' && state !== 'ended'
}
