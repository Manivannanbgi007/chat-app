// SYSTEM messages: the server writes one into a group for every change ("Asha added Ravi").
// Their content is a JSON string (API_DOCUMENTATION.md section 5). This file turns it into a sentence.
// Pure functions, tested in systemMessages.test.ts.
import type { ChatMessage, Conversation, SystemContent, SystemUser } from '../../types/chat'
import { KIND_LABEL } from '../media/mediaRules'
import { currentName } from './conversationInfo'

// Shown when the content cannot be understood (broken JSON, an event type this app does not know yet).
export const GENERIC_SYSTEM_TEXT = 'This group was updated.'

const KNOWN_EVENTS = new Set([
  'GROUP_CREATED',
  'MEMBERS_ADDED',
  'MEMBER_REMOVED',
  'MEMBER_LEFT',
  'ADMIN_ADDED',
  'ADMIN_REMOVED',
  'NAME_CHANGED',
  'DESCRIPTION_CHANGED',
  'SETTINGS_CHANGED',
])

function readUser(value: unknown): SystemUser | null {
  if (!value || typeof value !== 'object') return null
  const { id, name } = value as { id?: unknown; name?: unknown }
  if (typeof id !== 'string') return null
  return { id, name: typeof name === 'string' ? name : null }
}

// Reads the JSON safely. Returns null for anything that does not look like a known SYSTEM content.
export function parseSystemContent(content: string): SystemContent | null {
  let raw: unknown
  try {
    raw = JSON.parse(content)
  } catch {
    return null
  }
  if (!raw || typeof raw !== 'object') return null
  const data = raw as Record<string, unknown>
  if (typeof data.event !== 'string' || !KNOWN_EVENTS.has(data.event)) return null

  const actor = readUser(data.actor)
  const users = Array.isArray(data.users) ? data.users.map(readUser).filter((user): user is SystemUser => user !== null) : []
  // Only ADMIN_ADDED may come without an actor (the server made someone admin automatically).
  if (!actor && data.event !== 'ADMIN_ADDED') return null

  return {
    ...data,
    actor,
    users,
    name: typeof data.name === 'string' ? data.name : '',
    value: data.value === true,
  } as unknown as SystemContent
}

// How to call a person in the sentence: "You" / "you" for me, else their current name, the stored name, "Someone".
function nameOf(user: SystemUser, conversation: Conversation | undefined, meId: string, startOfSentence: boolean): string {
  if (user.id === meId) return startOfSentence ? 'You' : 'you'
  return currentName(conversation, user.id) ?? user.name ?? (startOfSentence ? 'Someone' : 'someone')
}

// "Ravi", "Ravi and Meena", "Ravi, Meena and Asha"
function joinNames(names: string[]): string {
  if (names.length <= 1) return names[0] ?? 'someone'
  return `${names.slice(0, -1).join(', ')} and ${names[names.length - 1]}`
}

// The sentence for one SYSTEM message, as the current user should read it.
export function describeSystemMessage(message: ChatMessage, conversation: Conversation | undefined, meId: string): string {
  const content = parseSystemContent(message.content)
  if (!content) return GENERIC_SYSTEM_TEXT

  const subject = (user: SystemUser) => nameOf(user, conversation, meId, true)
  const object = (users: SystemUser[]) => joinNames(users.map((user) => nameOf(user, conversation, meId, false)))

  switch (content.event) {
    case 'GROUP_CREATED':
      return `${subject(content.actor)} created group "${content.name}"`
    case 'MEMBERS_ADDED':
      return `${subject(content.actor)} added ${object(content.users)}`
    case 'MEMBER_REMOVED':
      return `${subject(content.actor)} removed ${object(content.users)}`
    case 'MEMBER_LEFT':
      return `${subject(content.actor)} left`
    case 'ADMIN_ADDED': {
      const plural = content.users.length > 1
      if (!content.actor) {
        if (!plural && content.users[0]?.id === meId) return "You're now an admin"
        const who = joinNames(content.users.map((user) => nameOf(user, conversation, meId, true)))
        return plural ? `${who} are now admins` : `${who} is now an admin`
      }
      return `${subject(content.actor)} made ${object(content.users)} ${plural ? 'admins' : 'an admin'}`
    }
    case 'ADMIN_REMOVED':
      return `${subject(content.actor)} dismissed ${object(content.users)} as admin`
    case 'NAME_CHANGED':
      return `${subject(content.actor)} changed the group name to "${content.name}"`
    case 'DESCRIPTION_CHANGED':
      return `${subject(content.actor)} changed the group description`
    case 'SETTINGS_CHANGED': {
      const who = content.value ? 'only admins' : 'all members'
      const what = content.setting === 'ONLY_ADMINS_CAN_SEND' ? 'send messages' : "edit this group's info"
      return `${subject(content.actor)} changed this group's settings to allow ${who} to ${what}`
    }
  }
}

// The chat list preview of a message: the sentence for SYSTEM messages, the text otherwise.
export function previewText(message: ChatMessage, conversation: Conversation | undefined, meId: string): string {
  if (message.type === 'SYSTEM') return describeSystemMessage(message, conversation, meId)
  // A media message: "Photo", "Voice message" ... plus the caption if there is one.
  if (message.type !== 'TEXT') {
    const label = KIND_LABEL[message.type]
    return message.content.trim() ? `${label}: ${message.content}` : label
  }
  return message.content
}
