import type { Attachment, Conversation, Message, SystemContent } from '../types/chat'
import type { PublicProfile } from '../types/user'

// Small builders so tests only mention the fields they care about.

export const ME = 'u-me'
export const OTHER = 'u-other'
export const THIRD = 'u-third'

export function profile(id: string, displayName: string): PublicProfile {
  return { id, username: displayName.toLowerCase().replace(/\s+/g, ''), displayName, profileImageUrl: null, about: null }
}

let counter = 0

// A message from the server. Defaults to "sent by the other person to me".
export function makeMessage(overrides: Partial<Message> = {}): Message {
  counter += 1
  const id = overrides.id ?? `m-${String(counter).padStart(4, '0')}`
  const mine = (overrides.senderId ?? OTHER) === ME
  return {
    id,
    conversationId: 'c1',
    senderId: OTHER,
    type: 'TEXT',
    content: `hello ${id}`,
    createdAt: new Date(Date.UTC(2026, 10, 4, 10, 0, counter)).toISOString(),
    status: mine ? 'SENT' : null,
    myReceipt: null,
    ...overrides,
  }
}

// A SYSTEM message about a group change (content is the JSON the server writes).
export function makeSystemMessage(content: SystemContent, overrides: Partial<Message> = {}): Message {
  return makeMessage({
    type: 'SYSTEM',
    senderId: content.actor?.id ?? OTHER,
    content: JSON.stringify(content),
    status: null,
    myReceipt: null,
    ...overrides,
  })
}

// A private chat between me and Ravi (the other person).
export function makeConversation(overrides: Partial<Conversation> = {}): Conversation {
  const other = overrides.otherUser ?? profile(OTHER, 'Ravi Kumar')
  return {
    id: 'c1',
    type: 'PRIVATE',
    createdAt: '2026-11-01T08:00:00.000000Z',
    lastMessageAt: null,
    participants: [profile(ME, 'Asha Rao'), other],
    otherUser: other,
    group: null,
    membership: { role: 'MEMBER', active: true, joinedAt: '2026-11-01T08:00:00.000000Z', leftAt: null },
    preferences: { mutedUntil: null, archived: false, pinned: false },
    ...overrides,
  }
}

// A group "Trip to Goa" with me (admin by default), Ravi and Meena.
export function makeGroup(
  overrides: Partial<Conversation> = {},
  options: { role?: 'ADMIN' | 'MEMBER'; active?: boolean; onlyAdminsCanSend?: boolean; onlyAdminsCanEdit?: boolean } = {},
): Conversation {
  const role = options.role ?? 'ADMIN'
  const active = options.active ?? true
  const participants = active
    ? [profile(ME, 'Asha Rao'), profile(OTHER, 'Ravi Kumar'), profile(THIRD, 'Meena Iyer')]
    : []
  const adminIds = active ? (role === 'ADMIN' ? [ME] : [OTHER]) : []
  return {
    id: 'g1',
    type: 'GROUP',
    createdAt: '2026-11-01T08:00:00.000000Z',
    lastMessageAt: null,
    participants,
    otherUser: null,
    group: {
      name: 'Trip to Goa',
      description: 'Planning the trip',
      imageUrl: null,
      createdBy: role === 'ADMIN' ? ME : OTHER,
      onlyAdminsCanSend: options.onlyAdminsCanSend ?? false,
      onlyAdminsCanEdit: options.onlyAdminsCanEdit ?? false,
      adminIds,
    },
    membership: {
      role: active ? role : 'MEMBER',
      active,
      joinedAt: '2026-11-01T08:00:00.000000Z',
      leftAt: active ? null : '2026-11-03T08:00:00.000000Z',
    },
    preferences: { mutedUntil: null, archived: false, pinned: false },
    ...overrides,
  }
}

// The file of a media message (an image of 640 x 480 by default).
export function makeAttachment(overrides: Partial<Attachment> = {}): Attachment {
  return {
    mediaId: 'media-1',
    kind: 'IMAGE',
    fileName: 'holiday.png',
    mimeType: 'image/png',
    sizeBytes: 123456,
    width: 640,
    height: 480,
    durationSeconds: null,
    hasThumbnail: true,
    ...overrides,
  }
}

// A media message from the server: the type follows the attachment kind, content is the caption.
export function makeMediaMessage(attachment: Partial<Attachment> = {}, overrides: Partial<Message> = {}): Message {
  const file = makeAttachment(attachment)
  return makeMessage({ type: file.kind, content: '', attachment: file, ...overrides })
}
