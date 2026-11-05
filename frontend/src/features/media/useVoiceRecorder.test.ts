import { act, renderHook } from '@testing-library/react'
import {
  MAX_VOICE_SECONDS,
  describeMicrophoneError,
  extensionFor,
  pickRecordingType,
  useVoiceRecorder,
  type VoiceResult,
} from './useVoiceRecorder'

// A stand-in for the browser's MediaRecorder: stop() delivers one chunk of "audio" and then fires onstop.
class FakeMediaRecorder {
  static supported = ['audio/webm;codecs=opus', 'audio/webm']
  static instances: FakeMediaRecorder[] = []
  static isTypeSupported = (type: string) => FakeMediaRecorder.supported.includes(type)

  state: 'inactive' | 'recording' = 'inactive'
  mimeType: string
  ondataavailable: ((event: { data: Blob }) => void) | null = null
  onstop: (() => void) | null = null

  stream: MediaStream

  constructor(stream: MediaStream, options?: { mimeType?: string }) {
    this.stream = stream
    this.mimeType = options?.mimeType ?? 'audio/webm'
    FakeMediaRecorder.instances.push(this)
  }

  start() {
    this.state = 'recording'
  }

  stop() {
    this.state = 'inactive'
    this.ondataavailable?.({ data: new Blob(['voice'], { type: this.mimeType }) })
    this.onstop?.()
  }
}

let stopTrack: ReturnType<typeof vi.fn>
let getUserMedia: ReturnType<typeof vi.fn>

beforeEach(() => {
  vi.useFakeTimers()
  FakeMediaRecorder.instances = []
  FakeMediaRecorder.supported = ['audio/webm;codecs=opus', 'audio/webm']
  stopTrack = vi.fn()
  getUserMedia = vi.fn().mockResolvedValue({ getTracks: () => [{ stop: stopTrack }] })
  vi.stubGlobal('MediaRecorder', FakeMediaRecorder)
  Object.defineProperty(navigator, 'mediaDevices', { value: { getUserMedia }, configurable: true })
})

afterEach(() => {
  vi.useRealTimers()
  vi.unstubAllGlobals()
})

async function startRecording(onAutoFinished?: (result: VoiceResult) => void) {
  const hook = renderHook(() => useVoiceRecorder({ onAutoFinished }))
  await act(async () => {
    await hook.result.current.start()
  })
  return hook
}

describe('format helpers', () => {
  it('prefers webm/opus, falls back to what the browser supports, and knows when nothing is supported', () => {
    expect(pickRecordingType()).toBe('audio/webm;codecs=opus')
    FakeMediaRecorder.supported = ['audio/mp4']
    expect(pickRecordingType()).toBe('audio/mp4')
    FakeMediaRecorder.supported = []
    expect(pickRecordingType()).toBe('') // record with the browser default
    vi.stubGlobal('MediaRecorder', undefined)
    expect(pickRecordingType()).toBeNull()
  })

  it('picks the file extension from the recording type', () => {
    expect(extensionFor('audio/webm;codecs=opus')).toBe('webm')
    expect(extensionFor('audio/mp4')).toBe('m4a')
    expect(extensionFor('audio/ogg;codecs=opus')).toBe('ogg')
  })

  it('explains microphone problems', () => {
    expect(describeMicrophoneError(new DOMException('x', 'NotAllowedError'))).toMatch(/denied/)
    expect(describeMicrophoneError(new DOMException('x', 'NotFoundError'))).toMatch(/No microphone/)
    expect(describeMicrophoneError(new DOMException('x', 'NotReadableError'))).toMatch(/another app/)
    expect(describeMicrophoneError(new Error('?'))).toMatch(/could not be started/)
  })
})

describe('useVoiceRecorder', () => {
  it('starts recording, counts seconds, and finish() returns the audio with the measured duration', async () => {
    const { result } = await startRecording()

    expect(result.current.status).toBe('recording')
    expect(getUserMedia).toHaveBeenCalledWith({ audio: true })
    expect(FakeMediaRecorder.instances[0].mimeType).toBe('audio/webm;codecs=opus')

    await act(async () => {
      vi.advanceTimersByTime(3200)
    })
    expect(result.current.seconds).toBe(3)

    let voice: Awaited<ReturnType<typeof result.current.finish>> = null
    await act(async () => {
      voice = await result.current.finish()
    })

    expect(voice).not.toBeNull()
    expect(voice!.durationSeconds).toBe(3)
    expect(voice!.fileName).toBe('voice-note.webm')
    expect(voice!.blob.size).toBeGreaterThan(0)
    expect(result.current.status).toBe('idle')
    expect(stopTrack).toHaveBeenCalled() // the microphone is released
  })

  it('throws away a recording shorter than one second', async () => {
    const { result } = await startRecording()
    await act(async () => {
      vi.advanceTimersByTime(400)
    })

    let voice: unknown = 'not set'
    await act(async () => {
      voice = await result.current.finish()
    })

    expect(voice).toBeNull()
    expect(result.current.error).toMatch(/too short/)
    expect(result.current.status).toBe('idle')
    expect(stopTrack).toHaveBeenCalled()
  })

  it('cancel() discards the recording and releases the microphone', async () => {
    const { result } = await startRecording()

    act(() => result.current.cancel())

    expect(result.current.status).toBe('idle')
    expect(result.current.seconds).toBe(0)
    expect(stopTrack).toHaveBeenCalled()
    expect(FakeMediaRecorder.instances[0].state).toBe('inactive')
  })

  it('releases the microphone when the component goes away while recording', async () => {
    const { unmount } = await startRecording()

    unmount()

    expect(stopTrack).toHaveBeenCalled()
  })

  it('shows a friendly message when the permission is denied', async () => {
    getUserMedia.mockRejectedValue(new DOMException('no', 'NotAllowedError'))
    const hook = renderHook(() => useVoiceRecorder())

    await act(async () => {
      await hook.result.current.start()
    })

    expect(hook.result.current.status).toBe('idle')
    expect(hook.result.current.error).toMatch(/denied/)
    expect(FakeMediaRecorder.instances).toHaveLength(0)
  })

  it('shows a message when the browser cannot record at all', async () => {
    vi.stubGlobal('MediaRecorder', undefined)
    const hook = renderHook(() => useVoiceRecorder())

    await act(async () => {
      await hook.result.current.start()
    })

    expect(hook.result.current.error).toMatch(/not supported/)
    expect(getUserMedia).not.toHaveBeenCalled()
  })

  it('stops by itself after five minutes and hands the recording over', async () => {
    const onAutoFinished = vi.fn()
    const { result } = await startRecording(onAutoFinished)

    await act(async () => {
      vi.advanceTimersByTime(MAX_VOICE_SECONDS * 1000 + 500)
    })

    expect(onAutoFinished).toHaveBeenCalledTimes(1)
    expect(onAutoFinished.mock.calls[0][0].durationSeconds).toBe(MAX_VOICE_SECONDS)
    expect(result.current.status).toBe('idle')
    expect(stopTrack).toHaveBeenCalled()
  })
})
