import { AxiosError, AxiosHeaders } from 'axios'
import { makeCall } from '../../test/callFixtures'
import { callErrorText, endedText, formatCallTime, formatDuration, historyOutcome, isMissed, mediaErrorText } from './callText'

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

describe('endedText', () => {
  it('says what happened from my point of view', () => {
    expect(endedText(makeCall({ status: 'MISSED', endReason: 'NO_ANSWER' }))).toBe('No answer')
    expect(endedText(makeCall({ status: 'MISSED', endReason: 'NO_ANSWER', direction: 'INCOMING' }))).toBe('Missed call')
    expect(endedText(makeCall({ status: 'MISSED', endReason: 'CANCELLED' }))).toBe('Call cancelled')
    expect(endedText(makeCall({ status: 'MISSED', endReason: 'CANCELLED', direction: 'INCOMING' }))).toBe('Missed call')
    expect(endedText(makeCall({ status: 'REJECTED', endReason: 'DECLINED' }))).toBe('Declined')
    expect(endedText(makeCall({ status: 'BUSY', endReason: 'BUSY' }))).toBe('Ravi Kumar is on another call')
    expect(endedText(makeCall({ status: 'BUSY', endReason: 'BUSY', direction: 'INCOMING' }))).toBe('Missed call')
    expect(endedText(makeCall({ status: 'ENDED', endReason: 'HANGUP' }))).toBe('Call ended')
    expect(endedText(makeCall({ status: 'ENDED', endReason: 'CONNECTION_LOST' }))).toBe('Connection lost')
  })
})

describe('history rows', () => {
  it('shows the duration of answered calls and the outcome of the others', () => {
    expect(historyOutcome(makeCall({ status: 'ENDED', durationSeconds: 65 }))).toBe('1:05')
    expect(historyOutcome(makeCall({ status: 'MISSED', endReason: 'NO_ANSWER' }))).toBe('No answer')
    expect(historyOutcome(makeCall({ status: 'MISSED', endReason: 'CANCELLED' }))).toBe('Cancelled')
    expect(historyOutcome(makeCall({ status: 'MISSED', direction: 'INCOMING' }))).toBe('Missed')
    expect(historyOutcome(makeCall({ status: 'REJECTED' }))).toBe('Declined')
    expect(historyOutcome(makeCall({ status: 'BUSY' }))).toBe('Busy')
    expect(historyOutcome(makeCall({ status: 'BUSY', direction: 'INCOMING' }))).toBe('Missed')
  })

  it('counts only incoming calls I could not take as missed', () => {
    expect(isMissed(makeCall({ status: 'MISSED', direction: 'INCOMING' }))).toBe(true)
    expect(isMissed(makeCall({ status: 'BUSY', direction: 'INCOMING' }))).toBe(true)
    expect(isMissed(makeCall({ status: 'MISSED', direction: 'OUTGOING' }))).toBe(false)
    expect(isMissed(makeCall({ status: 'REJECTED', direction: 'INCOMING' }))).toBe(false)
    expect(isMissed(makeCall({ status: 'ENDED', direction: 'INCOMING' }))).toBe(false)
  })

  it('formats durations and times', () => {
    expect(formatDuration(0)).toBe('0:00')
    expect(formatDuration(-3)).toBe('0:00')
    expect(formatDuration(42.7)).toBe('0:42')
    expect(formatDuration(3723)).toBe('1:02:03')

    const now = new Date(2026, 10, 4, 18, 0)
    expect(formatCallTime(new Date(2026, 10, 4, 9, 5).toISOString(), now)).toBe('09:05')
    expect(formatCallTime(new Date(2026, 10, 3, 22, 30).toISOString(), now)).toBe('Yesterday, 22:30')
    expect(formatCallTime(new Date(2026, 10, 1, 7, 0).toISOString(), now)).toBe('01 Nov, 07:00')
  })
})

describe('error texts', () => {
  it('explains the server errors', () => {
    expect(callErrorText(apiError(409, 'ALREADY_IN_CALL'))).toBe("You're already in a call.")
    expect(callErrorText(apiError(403, 'CALL_NOT_ALLOWED'), 'Ravi')).toBe("You can't call Ravi.")
    expect(callErrorText(apiError(409, 'CALL_NOT_RINGING'))).toBe('This call is no longer available.')
    expect(callErrorText(apiError(404))).toBe('This call is no longer available.')
    expect(callErrorText(apiError(500))).toBe('The call could not be started. Please try again.')
  })

  it('explains why the microphone or camera could not start', () => {
    expect(mediaErrorText({ name: 'NotAllowedError' }, 'VIDEO')).toBe('Allow access to your camera and microphone in the browser to make calls.')
    expect(mediaErrorText({ name: 'NotFoundError' }, 'AUDIO')).toBe('No microphone found on this device.')
    expect(mediaErrorText({ name: 'NotReadableError' }, 'AUDIO')).toBe('Your microphone is being used by another app.')
    expect(mediaErrorText({ name: 'NoMediaDevices' }, 'AUDIO')).toContain('secure https')
    expect(mediaErrorText(new Error('odd'), 'VIDEO')).toBe('Could not start your camera and microphone.')
  })
})
