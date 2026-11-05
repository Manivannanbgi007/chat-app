// Rules and small pure helpers for status / stories (API_DOCUMENTATION.md section 7.4).
// No React, no network: everything here is tested in statusRules.test.ts.
import { toAppError } from '../../services/api/appError'
import type { Status, StatusGroup } from '../../types/status'
import { hasUploadMessage, messageForUploadError } from '../media/mediaRules'

// The only background colours the server accepts for a text status (white text is readable on all of them).
export const STATUS_COLORS = ['#0F766E', '#1D4ED8', '#7C3AED', '#BE185D', '#B91C1C', '#C2410C', '#4D7C0F', '#334155'] as const

export const MAX_STATUS_TEXT = 700
export const MAX_STATUS_CAPTION = 700
export const MAX_ACTIVE_STATUSES = 30

// How long a text or photo status stays on screen in the viewer. Videos play to their end instead.
export const STATUS_DISPLAY_MS = 5000

// How often the Status page asks for new statuses while it is open.
export const FEED_POLL_MS = 30_000

// TanStack Query keys of the two lists (the page and the viewer update them in place).
export const STATUS_FEED_KEY = ['status-feed'] as const
export const MY_STATUSES_KEY = ['status-mine'] as const

// The colour after `current` (wraps around); used by the "change background colour" button.
export function nextColor(current: string): string {
  const index = STATUS_COLORS.indexOf(current as (typeof STATUS_COLORS)[number])
  return STATUS_COLORS[(index + 1) % STATUS_COLORS.length]
}

function clock(date: Date): string {
  return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

// "Just now", "1 minute ago", "12 minutes ago", "Today 10:42", "Yesterday 18:05". A status is at most 24 hours old.
export function formatStatusTime(iso: string, now: Date = new Date()): string {
  const then = new Date(iso)
  if (Number.isNaN(then.getTime())) return ''
  const ms = now.getTime() - then.getTime()
  if (ms < 60_000) return 'Just now' // also small clock differences (negative values)
  const minutes = Math.floor(ms / 60_000)
  if (minutes < 60) return minutes === 1 ? '1 minute ago' : `${minutes} minutes ago`
  if (then.toDateString() === now.toDateString()) return `Today ${clock(then)}`
  const yesterday = new Date(now)
  yesterday.setDate(now.getDate() - 1)
  if (then.toDateString() === yesterday.toDateString()) return `Yesterday ${clock(then)}`
  return `${then.toLocaleDateString()} ${clock(then)}`
}

function byLatestFirst(a: StatusGroup, b: StatusGroup): number {
  return Date.parse(b.latestAt) - Date.parse(a.latestAt)
}

// "Recent updates" = people with at least one status I have not seen; "Viewed updates" = everything seen.
// Newest first inside each part. `allViewed` is worked out from the statuses, so local changes count at once.
export function splitFeed(groups: StatusGroup[]): { recent: StatusGroup[]; viewed: StatusGroup[] } {
  const recent: StatusGroup[] = []
  const viewed: StatusGroup[] = []
  for (const group of groups) {
    if (group.statuses.length === 0) continue
    ;(group.statuses.every((status) => status.viewedByMe) ? viewed : recent).push(group)
  }
  return { recent: recent.sort(byLatestFirst), viewed: viewed.sort(byLatestFirst) }
}

// The feed after I saw one status (the ring segment turns grey at once, without waiting for the next poll).
export function markViewedInFeed(groups: StatusGroup[], statusId: string): StatusGroup[] {
  let changed = false
  const next = groups.map((group) => {
    if (!group.statuses.some((status) => status.id === statusId && !status.viewedByMe)) return group
    changed = true
    const statuses = group.statuses.map((status) => (status.id === statusId ? { ...status, viewedByMe: true } : status))
    return { ...group, statuses, allViewed: statuses.every((status) => status.viewedByMe) }
  })
  return changed ? next : groups
}

// Where the viewer starts in a person's statuses: the first one I have not seen (or the first one).
export function firstUnviewedIndex(statuses: Status[]): number {
  const index = statuses.findIndex((status) => !status.viewedByMe)
  return index < 0 ? 0 : index
}

// Which kind of status a picked file becomes (null = neither a photo nor a video).
export function statusKindOf(file: Pick<File, 'type' | 'name'>): 'IMAGE' | 'VIDEO' | null {
  if (file.type.startsWith('image/')) return 'IMAGE'
  if (file.type.startsWith('video/')) return 'VIDEO'
  // Some systems report no type: fall back to the extension.
  const extension = file.name.slice(file.name.lastIndexOf('.') + 1).toLowerCase()
  if (['jpg', 'jpeg', 'png', 'gif', 'webp'].includes(extension)) return 'IMAGE'
  if (['mp4', 'm4v', 'mov', 'webm'].includes(extension)) return 'VIDEO'
  return null
}

const STATUS_ERROR_TEXT: Record<string, string> = {
  STATUS_LIMIT_REACHED: `You already have ${MAX_ACTIVE_STATUSES} active status updates. Delete one or wait until one disappears.`,
  ATTACHMENT_NOT_FOUND: 'The uploaded file is no longer available. Press Post to upload it again.',
  ATTACHMENT_TYPE_MISMATCH: 'The file does not match the kind of status.',
  ATTACHMENT_ALREADY_USED: 'This file was already used. Press Post to upload it again.',
  DEPENDENCY_UNAVAILABLE: 'Status updates are not available right now. Please try again in a moment.',
}

// A friendly sentence for a failed post (or upload) of a status.
export function describeStatusError(error: unknown): string {
  const appError = toAppError(error)
  if (appError.errorCode && STATUS_ERROR_TEXT[appError.errorCode]) return STATUS_ERROR_TEXT[appError.errorCode]
  if (hasUploadMessage(appError.errorCode, appError.status)) {
    return messageForUploadError(appError.errorCode, appError.status, appError.kind)
  }
  switch (appError.kind) {
    case 'NETWORK':
      return 'No connection. Press Post to try again.'
    case 'SERVER':
      return STATUS_ERROR_TEXT.DEPENDENCY_UNAVAILABLE
    case 'RATE_LIMITED':
      return 'Too many requests. Wait a moment and try again.'
    case 'VALIDATION':
      return appError.message
    default:
      return 'The status could not be posted. Please try again.'
  }
}
