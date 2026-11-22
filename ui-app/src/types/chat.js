// Shapes of the conversation-service and message-service answers (see API_DOCUMENTATION.md sections 4 and 5)
// and of the realtime events (see WEBSOCKET_DESIGN.md).

/**
 * The state of a message I sent. Order matters: a status never moves backwards.
 * @typedef {'SENT' | 'DELIVERED' | 'READ'} ReceiptStatus
 */

/** @typedef {'PRIVATE' | 'GROUP'} ConversationType */
/** @typedef {'MEMBER' | 'ADMIN'} MemberRole */

/**
 * Only for groups: name, description and the group settings.
 * @typedef {object} GroupInfo
 * @property {string} name
 * @property {string | null} description
 * @property {string | null} imageUrl - stays null until media upload exists (Phase 5)
 * @property {string} createdBy
 * @property {boolean} onlyAdminsCanSend
 * @property {boolean} onlyAdminsCanEdit
 * @property {string[]} adminIds - empty when I am no longer a member
 */

/**
 * MY membership in the conversation.
 * @typedef {object} Membership
 * @property {MemberRole} role
 * @property {boolean} active - false = I left the group or was removed (I still see the old messages)
 * @property {string} joinedAt
 * @property {string | null} leftAt
 */

/**
 * MY settings for this chat (other people do not see them).
 * @typedef {object} ChatPreferences
 * @property {string | null} mutedUntil - muted while this time is in the future ("always" = year 9999)
 * @property {boolean} archived
 * @property {boolean} pinned
 */

/**
 * @typedef {object} Conversation
 * @property {string} id
 * @property {ConversationType} type
 * @property {string} createdAt
 * @property {string | null} lastMessageAt
 * @property {import('./user').PublicProfile[]} participants - the current members (empty for a group I am no longer in)
 * @property {import('./user').PublicProfile | null} otherUser - PRIVATE: the other person. GROUP: null
 * @property {GroupInfo | null} group - GROUP only
 * @property {Membership} membership
 * @property {ChatPreferences} preferences
 */

/** @typedef {'TEXT' | 'IMAGE' | 'VIDEO' | 'AUDIO' | 'VOICE' | 'DOCUMENT' | 'SYSTEM'} MessageType */

/**
 * The kinds of file a message can carry (the message type of a media message is the same word).
 * @typedef {'IMAGE' | 'VIDEO' | 'AUDIO' | 'VOICE' | 'DOCUMENT'} MediaKind
 */

/**
 * The file of a media message (API_DOCUMENTATION.md section 5, "Phase 5"). The file itself is fetched with a
 * short-lived signed link (see features/media/useAttachmentUrl.js), never stored here.
 * @typedef {object} Attachment
 * @property {string} mediaId
 * @property {MediaKind} kind
 * @property {string} fileName
 * @property {string} mimeType
 * @property {number} sizeBytes
 * @property {number | null} width - images only
 * @property {number | null} height - images only
 * @property {number | null} durationSeconds - audio / voice / video, when the uploader measured it
 * @property {boolean} hasThumbnail
 */

/**
 * What POST /api/media/upload answers (API_DOCUMENTATION.md section 6).
 * @typedef {object} Media
 * @property {string} id
 * @property {MediaKind} kind
 * @property {string} fileName
 * @property {string} mimeType
 * @property {number} sizeBytes
 * @property {number | null} width
 * @property {number | null} height
 * @property {number | null} durationSeconds
 * @property {boolean} hasThumbnail
 * @property {string} createdAt
 */

/**
 * A signed link straight to the object storage (valid 5 minutes).
 * @typedef {object} SignedUrl
 * @property {string} url
 * @property {string} expiresAt
 */

/**
 * A message exactly as the server sends it.
 * SYSTEM messages are written by the server for group changes; their content is JSON (see systemMessages.js).
 * @typedef {object} Message
 * @property {string} id
 * @property {string} conversationId
 * @property {string} senderId
 * @property {MessageType} type
 * @property {string} content
 * @property {string} createdAt
 * @property {ReceiptStatus | null} status - Only for messages I sent. null for messages sent to me (and SYSTEM messages).
 * @property {'DELIVERED' | 'READ' | null} myReceipt - Only for messages sent to me: what my devices already acknowledged.
 * @property {Attachment | null} [attachment] - Media messages: the file (content is then the caption). null / missing for text.
 */

/**
 * A message as the app keeps it. The extra fields are only used for messages that are still being sent:
 *   localStatus    SENDING = request in flight, FAILED = request failed (Retry button). Absent once the server has it.
 *   idempotencyKey the key sent with POST /api/messages. Retrying uses the SAME key, so the server never creates a duplicate.
 *   errorMessage   why sending failed (shown under the message).
 *   uploadProgress media messages that are still being sent: how much of the file is uploaded (0-100).
 *   previewUrl     a temporary link to the file on this computer, so the sender sees the picture / player at once.
 * @typedef {Message & {
 *   localStatus?: 'SENDING' | 'FAILED',
 *   idempotencyKey?: string,
 *   errorMessage?: string,
 *   uploadProgress?: number,
 *   previewUrl?: string,
 * }} ChatMessage
 */

/**
 * @typedef {object} MessagePage
 * @property {Message[]} items
 * @property {string | null} nextCursor
 * @property {boolean} hasMore
 */

/**
 * @typedef {object} ConversationSummary
 * @property {string} conversationId
 * @property {number} unreadCount
 * @property {Message | null} lastMessage
 */

/**
 * One recipient of a message I sent (GET /api/messages/{id}/receipts). SENT = not delivered yet.
 * @typedef {object} MessageReceiptInfo
 * @property {string} userId
 * @property {ReceiptStatus} status
 * @property {string | null} deliveredAt
 * @property {string | null} readAt
 */

/** @typedef {'EIGHT_HOURS' | 'ONE_WEEK' | 'ALWAYS'} MuteDuration */

// ---- SYSTEM message content (a JSON string in Message.content) ----

/**
 * A person named in a SYSTEM message: the name is the one they had at the time (null if it was unknown).
 * @typedef {object} SystemUser
 * @property {string} id
 * @property {string | null} name
 */

/**
 * The parsed content of a SYSTEM message. `event` is one of:
 *   GROUP_CREATED (actor, name), MEMBERS_ADDED (actor, users), MEMBER_REMOVED (actor, users), MEMBER_LEFT (actor),
 *   ADMIN_ADDED (actor, or null when the last admin left; users), ADMIN_REMOVED (actor, users), NAME_CHANGED (actor, name),
 *   DESCRIPTION_CHANGED (actor), SETTINGS_CHANGED (actor, setting: ONLY_ADMINS_CAN_SEND | ONLY_ADMINS_CAN_EDIT, value)
 * @typedef {object} SystemContent
 * @property {string} event
 * @property {SystemUser | null} actor
 * @property {SystemUser[]} users
 * @property {string} name
 * @property {string} [setting]
 * @property {boolean} value
 */

// ---- realtime events (server -> client) ----

/**
 * @typedef {object} ReceiptUpdate
 * @property {string} messageId
 * @property {string} conversationId
 * @property {string} userId
 * @property {'DELIVERED' | 'READ'} status - What the sender should show for the whole message (group: combined status).
 */

/**
 * A live event on /user/queue/... Its `type` decides what `data` holds:
 *   MESSAGE_CREATED  -> data: Message
 *   RECEIPT_UPDATED  -> data: ReceiptUpdate
 *   MESSAGE_ACK      -> data: { idempotencyKey, message: Message }
 *   ERROR            -> data: { code, message, idempotencyKey? }
 *   TYPING           -> data: { conversationId, userId, typing }
 * @typedef {object} RealtimeEvent
 * @property {string} type
 * @property {string} eventId
 * @property {string} conversationId
 * @property {*} data
 * @property {string} timestamp
 */

export {}
