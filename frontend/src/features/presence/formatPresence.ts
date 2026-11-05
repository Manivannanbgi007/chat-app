// Turns a presence answer into the text shown under the person's name. Pure functions (no React, no network),
// so they are easy to test (formatPresence.test.ts). Times are shown in the viewer's local time, 24 hours.
import type { Presence } from '../../types/presence'

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

function two(value: number): string {
  return String(value).padStart(2, '0')
}

function startOfDay(date: Date): number {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime()
}

// "last seen today at 10:32", "last seen yesterday at 21:05", "last seen 03 Nov at 18:20"
// (with the year when it is not this year: "last seen 03 Nov 2025 at 18:20").
export function formatLastSeen(iso: string, now: Date = new Date()): string | null {
  let seen = new Date(iso)
  if (Number.isNaN(seen.getTime())) return null
  // The server clock and this computer's clock can differ a little: never show a time in the future.
  if (seen.getTime() > now.getTime()) seen = now

  const time = `${two(seen.getHours())}:${two(seen.getMinutes())}`
  const daysAgo = Math.round((startOfDay(now) - startOfDay(seen)) / 86_400_000)
  if (daysAgo <= 0) return `last seen today at ${time}`
  if (daysAgo === 1) return `last seen yesterday at ${time}`

  const date = `${two(seen.getDate())} ${MONTHS[seen.getMonth()]}`
  const year = seen.getFullYear() === now.getFullYear() ? '' : ` ${seen.getFullYear()}`
  return `last seen ${date}${year} at ${time}`
}

export type PresenceLine = { kind: 'online' } | { kind: 'lastSeen'; text: string } | { kind: 'none' }

// What the chat header shows: "online", "last seen ...", or nothing
// (hidden by the person's privacy settings, or the person has never been seen).
export function presenceLine(presence: Presence | undefined, now: Date = new Date()): PresenceLine {
  if (!presence || !presence.visible) return { kind: 'none' }
  if (presence.online) return { kind: 'online' }
  if (!presence.lastSeenAt) return { kind: 'none' }
  const text = formatLastSeen(presence.lastSeenAt, now)
  return text ? { kind: 'lastSeen', text } : { kind: 'none' }
}
