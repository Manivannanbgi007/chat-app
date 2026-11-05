// Pure functions that turn a call into words for the screens (tested in callText.test.ts).
import { toAppError } from '../../services/api/appError'
import type { Call, CallType } from '../../types/call'

export function callKindLabel(type: CallType): string {
  return type === 'VIDEO' ? 'video call' : 'voice call'
}

// What the call screen shows when a call is over (the reason it ended, from MY point of view).
export function endedText(call: Call): string {
  const outgoing = call.direction === 'OUTGOING'
  switch (call.endReason) {
    case 'NO_ANSWER':
      return outgoing ? 'No answer' : 'Missed call'
    case 'CANCELLED':
      return outgoing ? 'Call cancelled' : 'Missed call'
    case 'DECLINED':
      return 'Declined'
    case 'BUSY':
      return outgoing ? `${call.otherUser.displayName} is on another call` : 'Missed call'
    case 'CONNECTION_LOST':
      return 'Connection lost'
    case 'HANGUP':
      return 'Call ended'
    default:
      return 'Call ended'
  }
}

// A missed call is an INCOMING call I never got to answer (it rang out, the caller gave up, or I was busy).
export function isMissed(call: Call): boolean {
  return call.direction === 'INCOMING' && (call.status === 'MISSED' || call.status === 'BUSY')
}

// "0:42", "12:05", "1:02:03"
export function formatDuration(totalSeconds: number): string {
  const seconds = Math.max(0, Math.floor(totalSeconds))
  const hours = Math.floor(seconds / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  const rest = String(seconds % 60).padStart(2, '0')
  return hours > 0 ? `${hours}:${String(minutes).padStart(2, '0')}:${rest}` : `${minutes}:${rest}`
}

// The second line of a row on the Calls page: the duration of an answered call, otherwise what happened.
export function historyOutcome(call: Call): string {
  const outgoing = call.direction === 'OUTGOING'
  switch (call.status) {
    case 'ENDED':
      return formatDuration(call.durationSeconds)
    case 'MISSED':
      if (!outgoing) return 'Missed'
      return call.endReason === 'CANCELLED' ? 'Cancelled' : 'No answer'
    case 'REJECTED':
      return 'Declined'
    case 'BUSY':
      return outgoing ? 'Busy' : 'Missed'
    case 'ACCEPTED':
      return 'In progress'
    case 'RINGING':
      return 'Ringing'
  }
}

// A friendly sentence for a failed POST /api/calls (or accept).
export function callErrorText(error: unknown, otherName?: string): string {
  const appError = toAppError(error)
  switch (appError.errorCode) {
    case 'ALREADY_IN_CALL':
      return "You're already in a call."
    case 'CALL_NOT_ALLOWED':
      return otherName ? `You can't call ${otherName}.` : "You can't call this person."
    case 'CALL_NOT_RINGING':
      return 'This call is no longer available.'
    case 'NOT_CALLEE':
      return 'This call is not for you.'
  }
  if (appError.kind === 'NOT_FOUND') return 'This call is no longer available.'
  if (appError.kind === 'NETWORK') return 'No connection. Check your internet and try again.'
  return 'The call could not be started. Please try again.'
}

// A friendly sentence for a failed getUserMedia (the browser could not give us the microphone / camera).
export function mediaErrorText(error: unknown, type: CallType): string {
  const devices = type === 'VIDEO' ? 'camera and microphone' : 'microphone'
  const name = error instanceof Error || (error && typeof error === 'object' && 'name' in error) ? (error as { name: string }).name : ''
  switch (name) {
    case 'NotAllowedError':
    case 'SecurityError':
      return `Allow access to your ${devices} in the browser to make calls.`
    case 'NotFoundError':
    case 'OverconstrainedError':
      return `No ${devices} found on this device.`
    case 'NotReadableError':
    case 'AbortError':
      return `Your ${devices} is being used by another app.`
    case 'NoMediaDevices':
      return 'This browser cannot make calls here (calls need a secure https connection).'
    default:
      return `Could not start your ${devices}.`
  }
}

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

// When a call happened, for the Calls page: "10:42" today, "Yesterday, 10:42", otherwise "02 Nov, 10:42".
export function formatCallTime(iso: string, now: Date = new Date()): string {
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) return ''
  const clock = `${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
  if (date.toDateString() === now.toDateString()) return clock
  const yesterday = new Date(now)
  yesterday.setDate(now.getDate() - 1)
  if (date.toDateString() === yesterday.toDateString()) return `Yesterday, ${clock}`
  return `${String(date.getDate()).padStart(2, '0')} ${MONTHS[date.getMonth()]}, ${clock}`
}
