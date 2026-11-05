// Shapes of the call-signaling-service answers (API_DOCUMENTATION.md section 7.3)
// and of the live call events on /user/queue/calls (WEBSOCKET_DESIGN.md section 9).
import type { PublicProfile } from './user'

export type CallType = 'AUDIO' | 'VIDEO'

// RINGING -> ACCEPTED -> ENDED, or from RINGING: REJECTED / MISSED. BUSY is final at once.
export type CallStatus = 'RINGING' | 'ACCEPTED' | 'ENDED' | 'MISSED' | 'REJECTED' | 'BUSY'

export type CallEndReason = 'HANGUP' | 'CONNECTION_LOST' | 'NO_ANSWER' | 'CANCELLED' | 'DECLINED' | 'BUSY'

export interface Call {
  id: string
  type: CallType
  status: CallStatus
  endReason: CallEndReason | null
  direction: 'OUTGOING' | 'INCOMING' // from MY point of view
  callerId: string
  calleeId: string
  otherUser: PublicProfile // the other person
  createdAt: string
  answeredAt: string | null
  endedAt: string | null
  durationSeconds: number
}

// One entry for RTCPeerConnection's iceServers: a STUN server, or the TURN server with short-lived credentials.
export interface IceServerConfig {
  urls: string[]
  username?: string
  credential?: string
}

// POST /api/calls and POST /api/calls/{id}/accept
export interface CallSession {
  call: Call
  iceServers: IceServerConfig[]
}

// GET /api/calls/history
export interface CallHistoryPage {
  items: Call[]
  page: number
  size: number
  totalItems: number
  hasMore: boolean
}

// ---- signaling (the messages that set up the browser-to-browser connection) ----

export type SignalType = 'OFFER' | 'ANSWER' | 'ICE'

// One ICE candidate ("a network address where you may reach me"), exactly as RTCIceCandidate describes it.
export interface IceCandidateData {
  candidate: string
  sdpMid: string | null
  sdpMLineIndex: number | null
}

// Body of POST /api/calls/{id}/signal
export interface SignalBody {
  type: SignalType
  sdp?: string
  candidates?: IceCandidateData[]
}

// data of a CALL_SIGNAL event
export interface CallSignalData {
  callId: string
  fromUserId: string
  toDeviceId: string
  signalType: SignalType
  sdp: string | null
  candidates: IceCandidateData[] | null
}

// The live events on /user/queue/calls.
export type CallEvent =
  | { type: 'CALL_INCOMING'; eventId: string; data: Call; timestamp: string }
  | { type: 'CALL_ACCEPTED'; eventId: string; data: { call: Call; calleeDeviceId: string }; timestamp: string }
  | { type: 'CALL_ENDED'; eventId: string; data: Call; timestamp: string }
  | { type: 'CALL_SIGNAL'; eventId: string; data: CallSignalData; timestamp: string }
