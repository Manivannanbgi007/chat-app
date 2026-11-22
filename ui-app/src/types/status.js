// Shapes of the status-service answers (status / stories, API_DOCUMENTATION.md section 7.4).

/** @typedef {'TEXT' | 'IMAGE' | 'VIDEO'} StatusType */

/**
 * A short copy of the file's details (the file itself is fetched through a signed link).
 * @typedef {object} StatusMedia
 * @property {'IMAGE' | 'VIDEO'} kind
 * @property {string} mimeType
 * @property {number | null} width
 * @property {number | null} height
 * @property {number | null} durationSeconds
 * @property {boolean} hasThumbnail
 */

/**
 * @typedef {object} Status
 * @property {string} id
 * @property {string} ownerId
 * @property {StatusType} type
 * @property {string | null} text - TEXT only
 * @property {string | null} backgroundColor - TEXT only, one of STATUS_COLORS
 * @property {string | null} caption - IMAGE / VIDEO only
 * @property {StatusMedia | null} media
 * @property {string} createdAt
 * @property {string} expiresAt - createdAt + 24 hours
 * @property {boolean} viewedByMe - always true for my own statuses
 */

/**
 * One of MY statuses: how many people (that I may see) viewed it.
 * @typedef {Status & { viewCount: number }} MyStatus
 */

/**
 * Everything one person posted that I may see, oldest first.
 * @typedef {object} StatusGroup
 * @property {import('./user').PublicProfile} owner
 * @property {Status[]} statuses
 * @property {string} latestAt
 * @property {boolean} allViewed
 */

/**
 * @typedef {object} StatusViewer
 * @property {import('./user').PublicProfile} user
 * @property {string} viewedAt
 */

/**
 * Who saw one of my statuses. viewersHidden = my own read receipts are off, so I see nobody.
 * @typedef {object} StatusViewers
 * @property {boolean} viewersHidden
 * @property {number} viewCount
 * @property {StatusViewer[]} viewers - newest first
 */

/** @typedef {'CONTACTS' | 'CONTACTS_EXCEPT' | 'ONLY_SHARE_WITH'} StatusPrivacyMode */

/**
 * @typedef {object} StatusPrivacy
 * @property {StatusPrivacyMode} mode
 * @property {string[]} userIds - the excepted people (CONTACTS_EXCEPT) or the chosen people (ONLY_SHARE_WITH)
 */

/**
 * The body of POST /api/statuses.
 *   TEXT:          { type: 'TEXT', text, backgroundColor, idempotencyKey }
 *   IMAGE / VIDEO: { type, mediaId, caption?, idempotencyKey }
 * @typedef {object} CreateStatusBody
 * @property {StatusType} type
 * @property {string} idempotencyKey
 * @property {string} [text]
 * @property {string} [backgroundColor]
 * @property {string} [mediaId]
 * @property {string} [caption]
 */

export {}
