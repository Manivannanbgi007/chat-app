import type { Call, CallSession } from '../types/call'
import { ME, OTHER, profile } from './chatFixtures'

// Small builders for call tests. A call defaults to "I call Ravi, it is ringing".
export function makeCall(overrides: Partial<Call> = {}): Call {
  const direction = overrides.direction ?? 'OUTGOING'
  return {
    id: 'call-1',
    type: 'VIDEO',
    status: 'RINGING',
    endReason: null,
    direction,
    callerId: direction === 'OUTGOING' ? ME : OTHER,
    calleeId: direction === 'OUTGOING' ? OTHER : ME,
    otherUser: profile(OTHER, 'Ravi Kumar'),
    createdAt: '2026-11-04T10:00:00.000000Z',
    answeredAt: null,
    endedAt: null,
    durationSeconds: 0,
    ...overrides,
  }
}

export const ICE_SERVERS = [
  { urls: ['stun:localhost:3478'] },
  { urls: ['turn:localhost:3478?transport=udp'], username: '1700000000:u-me', credential: 'secret' },
]

export function makeSession(overrides: Partial<Call> = {}): CallSession {
  return { call: makeCall(overrides), iceServers: ICE_SERVERS }
}

// A live event as it arrives on /user/queue/calls.
export function callEvent(type: string, data: unknown) {
  return { type, eventId: `e-${Math.random()}`, conversationId: null, data, timestamp: '2026-11-04T10:00:00Z' }
}
