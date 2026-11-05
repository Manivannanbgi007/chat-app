// Small pure helpers that answer questions about a conversation as the UI needs them:
// its title, who may do what, names of members, the "typing..." text.
// No React and no network here, so they are easy to test (conversationInfo.test.ts).
import type { ChatMessage, Conversation } from '../../types/chat'

const UNKNOWN_USER = 'Unknown user'

export function isGroup(conversation: Conversation | undefined): boolean {
  return conversation?.type === 'GROUP'
}

// What the chat is called: the group name, or the other person's name.
export function conversationTitle(conversation: Conversation): string {
  if (conversation.type === 'GROUP') return conversation.group?.name ?? 'Group'
  return conversation.otherUser?.displayName ?? UNKNOWN_USER
}

// Am I still in it? (Always true for private chats.)
export function isActiveMember(conversation: Conversation): boolean {
  return conversation.membership?.active !== false
}

export function isAdmin(conversation: Conversation): boolean {
  return conversation.type === 'GROUP' && isActiveMember(conversation) && conversation.membership?.role === 'ADMIN'
}

// Muted while mutedUntil is in the future.
export function isMuted(conversation: Conversation, now: number = Date.now()): boolean {
  const until = conversation.preferences?.mutedUntil
  return until != null && Date.parse(until) > now
}

export function isPinned(conversation: Conversation): boolean {
  return conversation.preferences?.pinned === true
}

export function isArchived(conversation: Conversation): boolean {
  return conversation.preferences?.archived === true
}

// Why I cannot write in this chat (null = I can).
export type SendBlock = 'NOT_A_MEMBER' | 'ONLY_ADMINS'

export function sendBlockReason(conversation: Conversation): SendBlock | null {
  if (!isActiveMember(conversation)) return 'NOT_A_MEMBER'
  if (conversation.type === 'GROUP' && conversation.group?.onlyAdminsCanSend && !isAdmin(conversation)) {
    return 'ONLY_ADMINS'
  }
  return null
}

// May I change the group name and description? (admins always; everybody when the group allows it)
export function canEditGroupInfo(conversation: Conversation): boolean {
  if (conversation.type !== 'GROUP' || !isActiveMember(conversation)) return false
  return isAdmin(conversation) || !conversation.group?.onlyAdminsCanEdit
}

// The name of a member as I know it now (null if they are not a current member, e.g. they left).
export function currentName(conversation: Conversation | undefined, userId: string): string | null {
  return conversation?.participants.find((person) => person.id === userId)?.displayName ?? null
}

// "You, Asha Rao, Ravi Kumar": the group members for the chat header (me first, then by name).
export function memberNamesLine(conversation: Conversation, meId: string): string {
  const others = conversation.participants
    .filter((person) => person.id !== meId)
    .map((person) => person.displayName)
    .sort((a, b) => a.localeCompare(b))
  const isMember = conversation.participants.some((person) => person.id === meId)
  return [...(isMember ? ['You'] : []), ...others].join(', ')
}

// The header line while people type. Private chat: "typing...". Group: who is typing.
export function typingText(conversation: Conversation | undefined, typingUserIds: string[], meId: string): string {
  const others = typingUserIds.filter((id) => id !== meId)
  if (others.length === 0) return ''
  if (!conversation || conversation.type !== 'GROUP') return 'typing...'
  const names = others.map((id) => currentName(conversation, id) ?? 'Someone')
  if (names.length === 1) return `${names[0]} is typing...`
  if (names.length === 2) return `${names[0]} and ${names[1]} are typing...`
  return `${names.length} people are typing...`
}

// A message sent to me that can be read / acknowledged (not mine, not still sending, not a SYSTEM message).
export function isIncoming(message: ChatMessage, meId: string): boolean {
  return message.senderId !== meId && !message.localStatus && message.type !== 'SYSTEM'
}

// A stable colour per person for sender names in groups (the same person always gets the same colour).
const SENDER_COLOURS = [
  'text-sky-700 dark:text-sky-300',
  'text-rose-700 dark:text-rose-300',
  'text-amber-700 dark:text-amber-300',
  'text-violet-700 dark:text-violet-300',
  'text-teal-700 dark:text-teal-300',
  'text-orange-700 dark:text-orange-300',
  'text-indigo-700 dark:text-indigo-300',
  'text-pink-700 dark:text-pink-300',
]

export function senderColourClass(userId: string): string {
  let hash = 0
  for (const char of userId) {
    hash = (hash * 31 + char.charCodeAt(0)) | 0
  }
  return SENDER_COLOURS[Math.abs(hash) % SENDER_COLOURS.length]
}
