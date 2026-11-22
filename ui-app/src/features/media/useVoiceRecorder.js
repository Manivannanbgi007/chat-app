// Records a voice note with the microphone (MediaRecorder).
//
// States: idle -> starting (waiting for the permission prompt) -> recording -> idle again.
// - finish(): stops and returns the recording (or null if it was too short)
// - cancel(): stops and throws the recording away
// The microphone is ALWAYS released (tracks stopped) when recording ends, is cancelled, or the component goes away.
// The duration is measured here (start to stop) and sent with the upload, so the player can show it.
//
// A finished recording is { blob, durationSeconds, mimeType, fileName } (fileName like "voice-note.webm").
import { useCallback, useEffect, useRef, useState } from 'react'

export const MIN_VOICE_SECONDS = 1
export const MAX_VOICE_SECONDS = 5 * 60

// The best format this browser can record, in order of preference. null = cannot record at all.
export function pickRecordingType() {
  if (typeof MediaRecorder === 'undefined') return null
  const candidates = ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4', 'audio/ogg;codecs=opus', 'audio/ogg']
  const supported = candidates.find((type) => MediaRecorder.isTypeSupported?.(type))
  return supported ?? ''
}

// "audio/webm;codecs=opus" -> "webm", "audio/mp4" -> "m4a"
export function extensionFor(mimeType) {
  if (mimeType.includes('mp4')) return 'm4a'
  if (mimeType.includes('ogg')) return 'ogg'
  return 'webm'
}

// A friendly sentence for what went wrong when asking for the microphone.
export function describeMicrophoneError(error) {
  const name = error instanceof DOMException ? error.name : ''
  if (name === 'NotAllowedError' || name === 'SecurityError') {
    return 'Microphone access was denied. Allow the microphone in your browser settings and try again.'
  }
  if (name === 'NotFoundError' || name === 'OverconstrainedError') return 'No microphone was found on this device.'
  if (name === 'NotReadableError') return 'The microphone is being used by another app.'
  return 'The voice recording could not be started.'
}

// options.onAutoFinished(result) is called when the 5-minute limit stops the recording by itself.
export function useVoiceRecorder(options = {}) {
  const [status, setStatus] = useState('idle') // 'idle' | 'starting' | 'recording'
  const [seconds, setSeconds] = useState(0)
  const [error, setError] = useState(null)

  const recorderRef = useRef(null)
  const streamRef = useRef(null)
  const chunksRef = useRef([])
  const startedAtRef = useRef(0)
  const timerRef = useRef(null)
  const finishingRef = useRef(false) // finish() is already running (a double click or the 5 minute limit)
  const autoFinishedRef = useRef(options.onAutoFinished)
  useEffect(() => {
    autoFinishedRef.current = options.onAutoFinished // always call the newest handler
  })

  // Releases the microphone and the timer. Safe to call many times.
  const release = useCallback(() => {
    if (timerRef.current) clearInterval(timerRef.current)
    timerRef.current = null
    streamRef.current?.getTracks().forEach((track) => track.stop())
    streamRef.current = null
    recorderRef.current = null
    chunksRef.current = []
    finishingRef.current = false
    setStatus('idle')
    setSeconds(0)
  }, [])

  // Stops the recorder and waits for the last chunk of audio.
  const stopRecorder = useCallback(() => {
    const recorder = recorderRef.current
    return new Promise((resolve) => {
      if (!recorder || recorder.state === 'inactive') {
        resolve(new Blob(chunksRef.current, { type: recorder?.mimeType ?? '' }))
        return
      }
      recorder.onstop = () => resolve(new Blob(chunksRef.current, { type: recorder.mimeType }))
      recorder.stop()
    })
  }, [])

  const cancel = useCallback(() => {
    const recorder = recorderRef.current
    if (recorder && recorder.state !== 'inactive') {
      recorder.onstop = null
      recorder.stop()
    }
    release()
  }, [release])

  const finish = useCallback(async () => {
    const recorder = recorderRef.current
    if (!recorder || finishingRef.current) return null
    finishingRef.current = true
    // stop the clock at once: stopping the recorder takes a moment and must not trigger a second finish
    if (timerRef.current) clearInterval(timerRef.current)
    timerRef.current = null
    const elapsed = (Date.now() - startedAtRef.current) / 1000
    if (elapsed < MIN_VOICE_SECONDS) {
      cancel() // too short: throw it away
      setError('The voice note is too short. Hold on a little longer.')
      return null
    }
    const mimeType = recorder.mimeType || 'audio/webm'
    const blob = await stopRecorder()
    release()
    return {
      blob,
      durationSeconds: Math.min(MAX_VOICE_SECONDS, Math.max(MIN_VOICE_SECONDS, Math.round(elapsed))),
      mimeType: blob.type || mimeType,
      fileName: `voice-note.${extensionFor(blob.type || mimeType)}`,
    }
  }, [cancel, release, stopRecorder])

  const start = useCallback(async () => {
    setError(null)
    if (!navigator.mediaDevices?.getUserMedia || pickRecordingType() === null) {
      setError('Voice notes are not supported in this browser.')
      return
    }
    setStatus('starting')
    let stream
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: true })
    } catch (caught) {
      setStatus('idle')
      setError(describeMicrophoneError(caught))
      return
    }

    try {
      const type = pickRecordingType()
      const recorder = new MediaRecorder(stream, type ? { mimeType: type } : undefined)
      chunksRef.current = []
      recorder.ondataavailable = (event) => {
        if (event.data && event.data.size > 0) chunksRef.current.push(event.data)
      }
      streamRef.current = stream
      recorderRef.current = recorder
      recorder.start()
      startedAtRef.current = Date.now()
      setSeconds(0)
      setStatus('recording')

      timerRef.current = setInterval(() => {
        const elapsed = Math.floor((Date.now() - startedAtRef.current) / 1000)
        setSeconds(elapsed)
        if (elapsed >= MAX_VOICE_SECONDS) {
          // Five minutes: stop by itself and hand the recording over.
          finish().then((result) => {
            if (result) autoFinishedRef.current?.(result)
          })
        }
      }, 250)
    } catch {
      stream.getTracks().forEach((track) => track.stop())
      release()
      setError('The voice recording could not be started.')
    }
  }, [finish, release])

  // Leaving the chat while recording: let go of the microphone.
  useEffect(() => cancel, [cancel])

  return { status, seconds, error, clearError: () => setError(null), start, finish, cancel }
}
