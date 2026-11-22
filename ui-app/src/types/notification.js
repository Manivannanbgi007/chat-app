// Shapes of the notification-service answers (see API_DOCUMENTATION.md section 7.2)
// and of the live NOTIFICATION event (see WEBSOCKET_DESIGN.md).
//
// The type is called AppNotification because "Notification" is already the browser's own desktop-notification class.

/**
 * MISSED_CALL (calls phase): title = the caller, body "Missed voice call" / "Missed video call", conversationId null.
 * @typedef {'NEW_MESSAGES' | 'GROUP_ADDED' | 'GROUP_REMOVED' | 'GROUP_ADMIN' | 'MISSED_CALL'} NotificationType
 */

/**
 * @typedef {object} AppNotification
 * @property {string} id
 * @property {NotificationType} type
 * @property {string} title - a person's name or the group name
 * @property {string} body - e.g. "3 new messages" or "Alice added you" (never the text of a message)
 * @property {string | null} conversationId
 * @property {number} count - NEW_MESSAGES: how many messages are waiting. Grows in place while unread.
 * @property {boolean} read
 * @property {string} createdAt
 * @property {string} updatedAt
 */

/**
 * GET /api/notifications
 * @typedef {object} NotificationPage
 * @property {AppNotification[]} items
 * @property {number} page
 * @property {number} size
 * @property {number} totalItems
 * @property {boolean} hasMore
 */

/**
 * GET / PUT /api/notifications/preferences
 * @typedef {object} NotificationPreferences
 * @property {boolean} pushEnabled
 * @property {boolean} messagesEnabled - private chats
 * @property {boolean} groupsEnabled - everything about groups
 * @property {boolean} callsEnabled - missed calls
 */

/**
 * The live event on /user/queue/notifications.
 * @typedef {object} NotificationEvent
 * @property {'NOTIFICATION'} type
 * @property {string} eventId
 * @property {string | null} conversationId
 * @property {AppNotification} data
 * @property {string} timestamp
 */

export {}
