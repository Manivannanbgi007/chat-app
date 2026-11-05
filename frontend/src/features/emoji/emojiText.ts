import { readStorage, writeStorage } from '../../utils/storage'

// ---- "a message that is only emoji" (shown big, like in other chat apps) ----

const MAX_BIG_EMOJI = 3

function splitGraphemes(text: string): string[] {
  if (typeof Intl !== 'undefined' && 'Segmenter' in Intl) {
    return Array.from(new Intl.Segmenter(undefined, { granularity: 'grapheme' }).segment(text), (part) => part.segment)
  }
  return Array.from(text) // very old browsers: still right for simple emoji
}

// One grapheme is an emoji when it has a pictograph (or is a flag: two regional indicators) and no letter or digit.
function isEmojiGrapheme(grapheme: string): boolean {
  if (/[\p{L}\p{N}]/u.test(grapheme)) return false
  return /\p{Extended_Pictographic}/u.test(grapheme) || /^\p{Regional_Indicator}{2}$/u.test(grapheme)
}

// How many emoji a text is made of when it is ONLY 1 to 3 emoji (spaces ignored); otherwise null.
export function bigEmojiCount(text: string): number | null {
  const graphemes = splitGraphemes(text.trim()).filter((part) => part.trim() !== '')
  if (graphemes.length === 0 || graphemes.length > MAX_BIG_EMOJI) return null
  return graphemes.every(isEmojiGrapheme) ? graphemes.length : null
}

// ---- recently used emoji (this browser only) ----

const RECENT_KEY = 'chat-app-recent-emoji'
const MAX_RECENT = 24

export function readRecentEmoji(): string[] {
  try {
    const parsed: unknown = JSON.parse(readStorage(RECENT_KEY) ?? '[]')
    return Array.isArray(parsed) ? parsed.filter((item): item is string => typeof item === 'string').slice(0, MAX_RECENT) : []
  } catch {
    return []
  }
}

// Puts the emoji first in the list of recent ones and returns the new list.
export function rememberEmoji(emoji: string): string[] {
  const next = [emoji, ...readRecentEmoji().filter((item) => item !== emoji)].slice(0, MAX_RECENT)
  writeStorage(RECENT_KEY, JSON.stringify(next))
  return next
}
