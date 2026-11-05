// Shapes of the conversation-service and message-service answers (see API_DOCUMENTATION.md sections 4 and 5)
// and of the realtime events (see WEBSOCKET_DESIGN.md).
import type { PublicProfile } from './user'

// The state of a message I sent. Order matters: a status never moves backwards.
export type ReceiptStatus = 'SENT' | 'DELIVERED' | 'READ'

export type ConversationType = 'PRIVATE' | 'GROUP'
export type MemberRole = 'MEMBER' | 'ADMIN'

// Only for groups: name, description and the group settings.
export interface GroupInfo {
  name: string
  description: string | null
  imageUrl: string | null // stays null until media upload exists (Phase 5)
  createdBy: string
  onlyAdminsCanSend: boolean
  onlyAdminsCanEdit: boolean
  adminIds: string[] // empty when I am no longer a member
}

// MY membership in the conversation.
export interface Membership {
  role: MemberRole
  active: boolean // false = I left the group or was removed (I still see the old messages)
  joinedAt: string
  leftAt: string | null
}

// MY settings for this chat (other people do not see them).
export interface ChatPreferences {
  mutedUntil: string | null // muted while this time is in the future ("always" = year 9999)
  archived: boolean
  pinned: boolean
}

export interface Conversation {
  id: string
  type: ConversationType
  createdAt: string
  lastMessageAt: string | null
  participants: PublicProfile[] // the current members (empty for a group I am no longer in)
  otherUser: PublicProfile | null // PRIVATE: the other person. GROUP: null
  group: GroupInfo | null // GROUP only
  membership: Membership
  preferences: ChatPreferences
}

export type MessageType = 'TEXT' | 'IMAGE' | 'VIDEO' | 'AUDIO' | 'VOICE' | 'DOCUMENT' | 'SYSTEM'

// The kinds of file a message can carry (the message type of a media message is the same word).
export type MediaKind = 'IMAGE' | 'VIDEO' | 'AUDIO' | 'VOICE' | 'DOCUMENT'

// The file of a media message (API_DOCUMENTATION.md section 5, "Phase 5"). The file itself is fetched with a
// short-lived signed link (see features/media/useAttachmentUrl.ts), never stored here.
export interface Attachment {
  mediaId: string
  kind: MediaKind
  fileName: string
  mimeType: string
  sizeBytes: number
  width: number | null // images only
  height: number | null // images only
  durationSeconds: number | null // audio / voice / video, when the uploader measured it
  hasThumbnail: boolean
}

// What POST /api/media/upload answers (API_DOCUMENTATION.md section 6).
export interface Media {
  id: string
  kind: MediaKind
  fileName: string
  mimeType: string
  sizeBytes: number
  width: number | null
  height: number | null
  durationSeconds: number | null
  hasThumbnail: boolean
  createdAt: string
}

// A signed link straight to the object storage (valid 5 minutes).
export interface SignedUrl {
  url: string
  expiresAt: string
}

// A message exactly as the server sends it.
// SYSTEM messages are written by the server for group changes; their content is JSON (see systemMessages.ts).
export interface Message {
  id: string
  conversationId: string
  senderId: string
  type: MessageType
  content: string
  createdAt: string
  // Only for messages I sent. null for messages sent to me (and for SYSTEM messages).
  status: ReceiptStatus | null
  // Only for messages sent to me: what my devices already acknowledged.
  myReceipt: 'DELIVERED' | 'READ' | null
  // Media messages: the file (content is then the caption, possibly empty). null / missing for text.
  attachment?: Attachment | null
}

// A message as the app keeps it. The extra fields are only used for messages that are still being sent.
export interface ChatMessage extends Message {
  // SENDING = request in flight, FAILED = request failed (Retry button). Absent once the server has it.
  localStatus?: 'SENDING' | 'FAILED'
  // The key sent with POST /api/messages. Retrying uses the SAME key, so the server never creates a duplicate.
  idempotencyKey?: string
  // Why sending failed (shown under the message).
  errorMessage?: string
  // Media messages that are still being sent: how much of the file is uploaded (0-100).
  uploadProgress?: number
  // A temporary link to the file on this computer, so the sender sees the picture / player at once.
  previewUrl?: string
}

export interface MessagePage {
  items: Message[]
  nextCursor: string | null
  hasMore: boolean
}

export interface ConversationSummary {
  conversationId: string
  unreadCount: number
  lastMessage: Message | null
}

// One recipient of a message I sent (GET /api/messages/{id}/receipts). SENT = not delivered yet.
export interface MessageReceiptInfo {
  userId: string
  status: ReceiptStatus
  deliveredAt: string | null
  readAt: string | null
}

export type MuteDuration = 'EIGHT_HOURS' | 'ONE_WEEK' | 'ALWAYS'

// ---- SYSTEM message content (a JSON string in Message.content) ----

// A person named in a SYSTEM message: the name is the one they had at the time (null if it was unknown).
export interface SystemUser {
  id: string
  name: string | null
}

export type SystemContent =
  | { event: 'GROUP_CREATED'; actor: SystemUser; name: string }
  | { event: 'MEMBERS_ADDED'; actor: SystemUser; users: SystemUser[] }
  | { event: 'MEMBER_REMOVED'; actor: SystemUser; users: SystemUser[] }
  | { event: 'MEMBER_LEFT'; actor: SystemUser }
  | { event: 'ADMIN_ADDED'; actor: SystemUser | null; users: SystemUser[] } // actor null: the last admin left
  | { event: 'ADMIN_REMOVED'; actor: SystemUser; users: SystemUser[] }
  | { event: 'NAME_CHANGED'; actor: SystemUser; name: string }
  | { event: 'DESCRIPTION_CHANGED'; actor: SystemUser }
  | { event: 'SETTINGS_CHANGED'; actor: SystemUser; setting: 'ONLY_ADMINS_CAN_SEND' | 'ONLY_ADMINS_CAN_EDIT'; value: boolean }

// ---- realtime events (server -> client) ----

export interface ReceiptUpdate {
  messageId: string
  conversationId: string
  userId: string
  // What the sender should show for the whole message (in a group: the combined status of all recipients).
  status: 'DELIVERED' | 'READ'
}

export type RealtimeEvent =
  | { type: 'MESSAGE_CREATED'; eventId: string; conversationId: string; data: Message; timestamp: string }
  | { type: 'RECEIPT_UPDATED'; eventId: string; conversationId: string; data: ReceiptUpdate; timestamp: string }
  | {
      type: 'MESSAGE_ACK'
      eventId: string
      conversationId: string
      data: { idempotencyKey: string; message: Message }
      timestamp: string
    }
  | {
      type: 'ERROR'
      eventId: string
      conversationId: string
      data: { code: string; message: string; idempotencyKey?: string }
      timestamp: string
    }
  | {
      type: 'TYPING'
      eventId: string
      conversationId: string
      data: { conversationId: string; userId: string; typing: boolean }
      timestamp: string
    }
