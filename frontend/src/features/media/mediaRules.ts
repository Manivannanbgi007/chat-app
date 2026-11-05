// What may be uploaded, mirroring the server's rules (API_DOCUMENTATION.md section 6).
// The checks here only save the user a pointless upload: the SERVER is the authority and checks the
// real bytes of the file again. Pure functions, tested in mediaRules.test.ts.
import type { MediaKind } from '../../types/chat'

const MB = 1024 * 1024

interface KindRules {
  label: string // "photo" - used in sentences
  maxBytes: number
  extensions: string[] // lower case, without the dot
  mimeTypes: string[]
}

export const MEDIA_RULES: Record<MediaKind, KindRules> = {
  IMAGE: {
    label: 'photo',
    maxBytes: 10 * MB,
    extensions: ['jpg', 'jpeg', 'png', 'gif', 'webp'],
    mimeTypes: ['image/jpeg', 'image/png', 'image/gif', 'image/webp'],
  },
  VIDEO: {
    label: 'video',
    maxBytes: 100 * MB,
    extensions: ['mp4', 'm4v', 'mov', 'webm'],
    mimeTypes: ['video/mp4', 'video/quicktime', 'video/webm'],
  },
  AUDIO: {
    label: 'audio file',
    maxBytes: 25 * MB,
    extensions: ['mp3', 'm4a', 'ogg', 'oga', 'opus', 'wav', 'webm'],
    mimeTypes: [
      'audio/mpeg',
      'audio/mp3',
      'audio/mp4',
      'audio/x-m4a',
      'audio/m4a',
      'audio/aac',
      'audio/ogg',
      'audio/opus',
      'audio/wav',
      'audio/x-wav',
      'audio/wave',
      'audio/webm',
    ],
  },
  // Voice notes are recorded by the app itself, so only the size matters here.
  VOICE: {
    label: 'voice note',
    maxBytes: 25 * MB,
    extensions: ['webm', 'ogg', 'm4a', 'mp4', 'mp3', 'wav'],
    mimeTypes: ['audio/webm', 'audio/ogg', 'audio/mp4', 'audio/mpeg', 'audio/wav'],
  },
  DOCUMENT: {
    label: 'document',
    maxBytes: 25 * MB,
    extensions: ['pdf', 'txt', 'csv', 'docx', 'xlsx', 'pptx'],
    mimeTypes: [
      'application/pdf',
      'text/plain',
      'text/csv',
      'application/vnd.ms-excel', // some browsers report .csv like this
      'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
      'application/vnd.openxmlformats-officedocument.presentationml.presentation',
    ],
  },
}

// The value of the "accept" attribute of the file picker for a kind (the browser then shows matching files).
export function acceptAttribute(kind: MediaKind): string {
  return MEDIA_RULES[kind].extensions.map((extension) => `.${extension}`).join(',')
}

// 1536 -> "1.5 KB", 10485760 -> "10 MB"
export function humanSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  const units = ['KB', 'MB', 'GB']
  let value = bytes / 1024
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit += 1
  }
  const rounded = value >= 10 || Number.isInteger(value) ? Math.round(value) : Math.round(value * 10) / 10
  return `${rounded} ${units[unit]}`
}

// 75 -> "1:15", 5 -> "0:05"
export function formatDuration(totalSeconds: number): string {
  const seconds = Math.max(0, Math.round(totalSeconds))
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}

function extensionOf(fileName: string): string {
  const dot = fileName.lastIndexOf('.')
  return dot < 0 ? '' : fileName.slice(dot + 1).toLowerCase()
}

// Returns a sentence that says why the file cannot be sent, or null when it looks fine.
export function validateFile(file: Pick<File, 'name' | 'size' | 'type'>, kind: MediaKind): string | null {
  const rules = MEDIA_RULES[kind]
  if (file.size === 0) return 'This file is empty.'
  if (file.size > rules.maxBytes) {
    return `This ${rules.label} is too large (${humanSize(file.size)}). The limit is ${humanSize(rules.maxBytes)}.`
  }
  // The name must end with an allowed extension, and the type reported by the browser (it can be empty)
  // must be an allowed one too.
  const typeOk = file.type === '' || rules.mimeTypes.includes(file.type.toLowerCase())
  if (!rules.extensions.includes(extensionOf(file.name)) || !typeOk) {
    return `This type of file cannot be sent as a ${rules.label}. Allowed: ${rules.extensions.join(', ')}.`
  }
  return null
}

// Which kind a dropped or pasted file belongs to (the picker menu already knows the kind).
export function guessKind(file: Pick<File, 'type'>): MediaKind {
  if (file.type.startsWith('image/')) return 'IMAGE'
  if (file.type.startsWith('video/')) return 'VIDEO'
  if (file.type.startsWith('audio/')) return 'AUDIO'
  return 'DOCUMENT'
}

// The short text for the chat list and notifications: "Photo", "Voice message" ...
export const KIND_LABEL: Record<MediaKind, string> = {
  IMAGE: 'Photo',
  VIDEO: 'Video',
  AUDIO: 'Audio',
  VOICE: 'Voice message',
  DOCUMENT: 'Document',
}

const UPLOAD_ERROR_TEXT: Record<string, string> = {
  FILE_TOO_LARGE: 'The file is too large.',
  UNSUPPORTED_TYPE: 'This type of file is not allowed.',
  CONTENT_MISMATCH: 'The file is not what its name says, so it was rejected.',
  INVALID_IMAGE: 'This image could not be read or is too big in pixels.',
  MALWARE_DETECTED: 'The file was rejected by the security scan.',
  ATTACHMENT_NOT_FOUND: 'The uploaded file is no longer available. Please attach it again.',
  ATTACHMENT_ALREADY_USED: 'This file was already sent in another message.',
  ATTACHMENT_TYPE_MISMATCH: 'The file does not match the kind of message.',
  VALIDATION: 'The file or the message was not accepted.',
}

// A friendly sentence for a failed upload or media message. `kind` / `code` / `status` come from the AppError.
export function messageForUploadError(code: string | null, status: number | null, kind: string | null): string {
  if (code && UPLOAD_ERROR_TEXT[code]) return UPLOAD_ERROR_TEXT[code]
  if (status === 413) return 'The file is too large.'
  if (status === 415) return 'This type of file is not allowed.'
  if (kind === 'NETWORK') return 'No connection. Press Retry when you are back online.'
  if (kind === 'RATE_LIMITED') return 'You are uploading too fast. Wait a moment and press Retry.'
  return 'The file could not be sent. Press Retry.'
}

// Does the upload table (or the status) have a specific sentence for this error?
export function hasUploadMessage(code: string | null, status: number | null): boolean {
  return (code !== null && code in UPLOAD_ERROR_TEXT && code !== 'VALIDATION') || status === 413 || status === 415
}

// Errors where trying again with the same file cannot work: the bubble is removed and the user is told why.
// (A lost connection or a busy server is different: the bubble stays as FAILED with a Retry button.)
export function isRejection(code: string | null, status: number | null): boolean {
  if (code && ['FILE_TOO_LARGE', 'UNSUPPORTED_TYPE', 'CONTENT_MISMATCH', 'INVALID_IMAGE', 'MALWARE_DETECTED'].includes(code)) {
    return true
  }
  return status === 413 || status === 415
}
