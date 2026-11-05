import {
  KIND_LABEL,
  MEDIA_RULES,
  acceptAttribute,
  formatDuration,
  guessKind,
  humanSize,
  isRejection,
  messageForUploadError,
  validateFile,
} from './mediaRules'

const MB = 1024 * 1024
const file = (name: string, size: number, type: string) => ({ name, size, type })

describe('validateFile', () => {
  it('accepts normal files of every kind', () => {
    expect(validateFile(file('a.jpg', 1000, 'image/jpeg'), 'IMAGE')).toBeNull()
    expect(validateFile(file('a.PNG', 1000, 'image/png'), 'IMAGE')).toBeNull()
    expect(validateFile(file('clip.mp4', 5 * MB, 'video/mp4'), 'VIDEO')).toBeNull()
    expect(validateFile(file('clip.mov', 5 * MB, 'video/quicktime'), 'VIDEO')).toBeNull()
    expect(validateFile(file('song.mp3', MB, 'audio/mpeg'), 'AUDIO')).toBeNull()
    expect(validateFile(file('report.pdf', MB, 'application/pdf'), 'DOCUMENT')).toBeNull()
    expect(validateFile(file('voice-note.webm', 5000, 'audio/webm'), 'VOICE')).toBeNull()
  })

  it('accepts a file whose browser type is empty if the extension is allowed (some systems do not know .m4a)', () => {
    expect(validateFile(file('song.m4a', MB, ''), 'AUDIO')).toBeNull()
  })

  it('rejects an empty file', () => {
    expect(validateFile(file('a.png', 0, 'image/png'), 'IMAGE')).toMatch(/empty/)
  })

  it('applies the size limit of each kind (image 10 MB, video 100 MB, document / audio 25 MB)', () => {
    expect(validateFile(file('a.png', 10 * MB, 'image/png'), 'IMAGE')).toBeNull()
    expect(validateFile(file('a.png', 10 * MB + 1, 'image/png'), 'IMAGE')).toMatch(/too large.*10 MB/)
    expect(validateFile(file('a.mp4', 100 * MB, 'video/mp4'), 'VIDEO')).toBeNull()
    expect(validateFile(file('a.mp4', 101 * MB, 'video/mp4'), 'VIDEO')).toMatch(/100 MB/)
    expect(validateFile(file('a.pdf', 26 * MB, 'application/pdf'), 'DOCUMENT')).toMatch(/25 MB/)
    expect(validateFile(file('a.mp3', 26 * MB, 'audio/mpeg'), 'AUDIO')).toMatch(/25 MB/)
  })

  it('rejects the wrong extension or the wrong type, and dangerous files', () => {
    expect(validateFile(file('a.exe', 1000, 'application/x-msdownload'), 'DOCUMENT')).toMatch(/cannot be sent/)
    expect(validateFile(file('a.svg', 1000, 'image/svg+xml'), 'IMAGE')).toMatch(/cannot be sent/)
    expect(validateFile(file('page.html', 1000, 'text/html'), 'DOCUMENT')).toMatch(/cannot be sent/)
    expect(validateFile(file('a.png', 1000, 'application/pdf'), 'IMAGE')).toMatch(/cannot be sent/) // name and type disagree
    expect(validateFile(file('noextension', 1000, 'image/png'), 'IMAGE')).toMatch(/cannot be sent/)
    expect(validateFile(file('a.mp4', 1000, 'video/mp4'), 'IMAGE')).toMatch(/cannot be sent/) // wrong kind
  })
})

describe('helpers', () => {
  it('writes sizes in a human way', () => {
    expect(humanSize(512)).toBe('512 B')
    expect(humanSize(1536)).toBe('1.5 KB')
    expect(humanSize(10 * MB)).toBe('10 MB')
    expect(humanSize(123456)).toBe('121 KB')
    expect(humanSize(2.5 * 1024 * MB)).toBe('2.5 GB')
  })

  it('writes durations as m:ss', () => {
    expect(formatDuration(5)).toBe('0:05')
    expect(formatDuration(75)).toBe('1:15')
    expect(formatDuration(300)).toBe('5:00')
    expect(formatDuration(-3)).toBe('0:00')
  })

  it('guesses the kind of a dropped or pasted file', () => {
    expect(guessKind({ type: 'image/png' })).toBe('IMAGE')
    expect(guessKind({ type: 'video/mp4' })).toBe('VIDEO')
    expect(guessKind({ type: 'audio/mpeg' })).toBe('AUDIO')
    expect(guessKind({ type: 'application/pdf' })).toBe('DOCUMENT')
    expect(guessKind({ type: '' })).toBe('DOCUMENT')
  })

  it('has a file picker filter and a chat list label for every kind', () => {
    expect(acceptAttribute('IMAGE')).toBe('.jpg,.jpeg,.png,.gif,.webp')
    for (const kind of Object.keys(MEDIA_RULES) as (keyof typeof MEDIA_RULES)[]) {
      expect(KIND_LABEL[kind]).toBeTruthy()
    }
    expect(KIND_LABEL.VOICE).toBe('Voice message')
  })
})

describe('upload errors', () => {
  it('has a sentence for each server error code', () => {
    expect(messageForUploadError('FILE_TOO_LARGE', 413, null)).toMatch(/too large/)
    expect(messageForUploadError('UNSUPPORTED_TYPE', 415, null)).toMatch(/not allowed/)
    expect(messageForUploadError('CONTENT_MISMATCH', 415, null)).toMatch(/not what its name says/)
    expect(messageForUploadError('INVALID_IMAGE', 400, 'VALIDATION')).toMatch(/image/)
    expect(messageForUploadError('MALWARE_DETECTED', 422, null)).toMatch(/security/)
    expect(messageForUploadError('ATTACHMENT_ALREADY_USED', 409, null)).toMatch(/already sent/)
  })

  it('falls back to the status, then to a generic retry text', () => {
    expect(messageForUploadError(null, 413, null)).toMatch(/too large/)
    expect(messageForUploadError(null, 415, null)).toMatch(/not allowed/)
    expect(messageForUploadError(null, null, 'NETWORK')).toMatch(/No connection/)
    expect(messageForUploadError(null, 500, 'SERVER')).toMatch(/Retry/)
  })

  it('knows which errors can never succeed (the bubble is removed) and which can (Retry)', () => {
    expect(isRejection('CONTENT_MISMATCH', 415)).toBe(true)
    expect(isRejection('INVALID_IMAGE', 400)).toBe(true)
    expect(isRejection(null, 413)).toBe(true)
    expect(isRejection(null, 415)).toBe(true)
    expect(isRejection(null, null)).toBe(false) // network down
    expect(isRejection(null, 503)).toBe(false)
    expect(isRejection('ATTACHMENT_NOT_FOUND', 404)).toBe(false)
  })
})
