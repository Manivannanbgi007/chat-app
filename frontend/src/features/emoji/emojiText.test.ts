import { bigEmojiCount, readRecentEmoji, rememberEmoji } from './emojiText'
import { ALL_EMOJI, EMOJI_GROUPS, searchEmoji } from './emojiData'

describe('bigEmojiCount', () => {
  it('counts a message that is only 1 to 3 emoji', () => {
    expect(bigEmojiCount('😀')).toBe(1)
    expect(bigEmojiCount('😀😂')).toBe(2)
    expect(bigEmojiCount('  😀 😂 👍 ')).toBe(3)
  })

  it('counts a skin tone, a family and a flag as ONE emoji each', () => {
    expect(bigEmojiCount('👍🏽')).toBe(1)
    expect(bigEmojiCount('👨‍👩‍👧')).toBe(1)
    expect(bigEmojiCount('🇮🇳')).toBe(1)
    expect(bigEmojiCount('❤️')).toBe(1)
  })

  it('is null for text, text with emoji, more than 3 emoji, numbers and nothing', () => {
    expect(bigEmojiCount('hello')).toBeNull()
    expect(bigEmojiCount('hi 😀')).toBeNull()
    expect(bigEmojiCount('😀😀😀😀')).toBeNull()
    expect(bigEmojiCount('123')).toBeNull()
    expect(bigEmojiCount('1️⃣')).toBeNull()
    expect(bigEmojiCount('')).toBeNull()
    expect(bigEmojiCount('   ')).toBeNull()
  })
})

describe('recent emoji', () => {
  beforeEach(() => localStorage.clear())

  it('remembers the newest first, without repeats, at most 24', () => {
    expect(readRecentEmoji()).toEqual([])
    rememberEmoji('😀')
    rememberEmoji('👍')
    expect(rememberEmoji('😀')).toEqual(['😀', '👍'])
    for (let i = 0; i < 30; i++) rememberEmoji(String.fromCodePoint(0x1f600 + i))
    expect(readRecentEmoji()).toHaveLength(24)
  })

  it('ignores damaged saved data', () => {
    localStorage.setItem('chat-app-recent-emoji', '{not json')
    expect(readRecentEmoji()).toEqual([])
    localStorage.setItem('chat-app-recent-emoji', '[1, "😀", null]')
    expect(readRecentEmoji()).toEqual(['😀'])
  })
})

describe('emoji data and search', () => {
  it('offers many emoji in several groups, each with a name', () => {
    expect(EMOJI_GROUPS.length).toBeGreaterThanOrEqual(8)
    expect(ALL_EMOJI.length).toBeGreaterThan(400)
    expect(ALL_EMOJI.every((item) => item.emoji.length > 0 && item.name.length > 0)).toBe(true)
  })

  it('finds by any word of the name, all words must match', () => {
    expect(searchEmoji('heart eyes').map((item) => item.emoji)).toContain('😍')
    expect(searchEmoji('HEART').length).toBeGreaterThan(5)
    expect(searchEmoji('pizza').map((item) => item.emoji)).toEqual(['🍕'])
    expect(searchEmoji('zzzzzz')).toEqual([])
    expect(searchEmoji('   ')).toEqual([])
  })
})
