// Shapes of the notification-service answers (see API_DOCUMENTATION.md section 7.2)
// and of the live NOTIFICATION event (see WEBSOCKET_DESIGN.md).
//
// The type is called AppNotification because "Notification" is already the browser's own desktop-notification class.

// MISSED_CALL (calls phase): title = the caller, body "Missed voice call" / "Missed video call", conversationId null.
export type NotificationType = 'NEW_MESSAGES' | 'GROUP_ADDED' | 'GROUP_REMOVED' | 'GROUP_ADMIN' | 'MISSED_CALL'

export interface AppNotification {
  id: string
  type: NotificationType
  title: string // a person's name or the group name
  body: string // e.g. "3 new messages" or "Alice added you" (never the text of a message)
  conversationId: string | null
  count: number // NEW_MESSAGES: how many messages are waiting. Grows in place while unread.
  read: boolean
  createdAt: string
  updatedAt: string
}

// GET /api/notifications
export interface NotificationPage {
  items: AppNotification[]
  page: number
  size: number
  totalItems: number
  hasMore: boolean
}

// GET / PUT /api/notifications/preferences
export interface NotificationPreferences {
  pushEnabled: boolean
  messagesEnabled: boolean // private chats
  groupsEnabled: boolean // everything about groups
  callsEnabled: boolean // missed calls
}

// The live event on /user/queue/notifications.
export interface NotificationEvent {
  type: 'NOTIFICATION'
  eventId: string
  conversationId: string | null
  data: AppNotification
  timestamp: string
}
