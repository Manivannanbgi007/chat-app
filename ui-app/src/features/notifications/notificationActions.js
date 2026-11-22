// The code that connects the notification store to the server and to the live WebSocket events.
// (The store itself only changes data: store/notificationStore.js.)
import * as notificationApi from '../../services/api/notificationApi'
import { useNotificationStore } from '../../store/notificationStore'

const store = () => useNotificationStore.getState()

const PAGE_SIZE = 20
// A burst of live events shares one "ask the server for the real number" request.
const RECONCILE_DELAY_MS = 500
// The server marks a chat's notification as read a moment AFTER I opened the chat (it reacts to the read receipt).
const AFTER_OPEN_DELAYS_MS = [3000, 8000]

// ---- the unread count (the bell badge) ----

export async function refreshUnreadCount() {
  try {
    store().setUnreadCount(await notificationApi.getUnreadCount())
  } catch {
    // Keep the number we have: the next event or reconnect asks again.
  }
}

let reconcileTimer = null

// After live events the badge was changed by our own guess; this asks the server for the true number.
function scheduleReconcile() {
  if (reconcileTimer) clearTimeout(reconcileTimer)
  reconcileTimer = setTimeout(() => {
    reconcileTimer = null
    refreshUnreadCount()
  }, RECONCILE_DELAY_MS)
}

// Opening a chat: ask for the number again a few seconds later (see AFTER_OPEN_DELAYS_MS).
// Returns the function that cancels the pending asks (the chat was closed).
export function refreshUnreadCountAfterOpeningChat() {
  const timers = AFTER_OPEN_DELAYS_MS.map((delay) => setTimeout(() => refreshUnreadCount(), delay))
  return () => timers.forEach((timer) => clearTimeout(timer))
}

// ---- the switches (Settings > Notifications) ----

// Loads the switches once after login, so the bell panel and the desktop pop-ups know what is allowed.
export async function refreshPreferences() {
  try {
    store().setPreferences(await notificationApi.getNotificationPreferences())
  } catch {
    // Unknown stays unknown: nothing is hidden because of it.
  }
}

// ---- the list ----

// Loads the newest notifications (when the panel / page opens).
export async function loadFirstNotifications() {
  if (store().loading) return
  store().setLoading(true)
  try {
    store().setFirstPage(await notificationApi.getNotifications(0, PAGE_SIZE))
  } catch (error) {
    store().setLoading(false)
    throw error
  }
}

// "Load more": the next page.
export async function loadMoreNotifications() {
  if (store().loading || !store().hasMore) return
  store().setLoading(true)
  try {
    store().appendPage(await notificationApi.getNotifications(store().page + 1, PAGE_SIZE))
  } catch (error) {
    store().setLoading(false)
    throw error
  }
}

// Clicking a notification: mark it read (the badge drops at once) and tell the caller where to go
// (the chat, the Calls page for a missed call, or the chat list).
// If the server refuses, we ask it for the true state again.
export async function openNotification(notification) {
  if (!notification.read) {
    store().markReadLocal(notification.id)
    try {
      await notificationApi.markNotificationRead(notification.id)
    } catch {
      refreshUnreadCount()
      loadFirstNotifications().catch(() => undefined)
    }
  }
  return notificationPath(notification)
}

// Where a notification leads: the chat, the Calls page for a missed call, otherwise the chat list. It always leads
// somewhere, so a click is never "nothing happens".
export function notificationPath(notification) {
  if (notification.type === 'MISSED_CALL') return '/calls'
  return notification.conversationId ? `/chats/${notification.conversationId}` : '/chats'
}

export async function markAllNotificationsRead() {
  store().markAllReadLocal()
  try {
    await notificationApi.markAllNotificationsRead()
  } catch {
    refreshUnreadCount()
    loadFirstNotifications().catch(() => undefined)
  }
}

// ---- live events ----

// A NOTIFICATION event: a notification was created, or an unread one got more messages.
export function handleNotificationEvent(event) {
  if (!event || typeof event !== 'object' || !('type' in event) || event.type !== 'NOTIFICATION') return
  const data = event.data
  if (!data || typeof data.id !== 'string') return
  store().applyLive(data)
  scheduleReconcile()
}

// Starts listening. Returns the function that stops everything (logout / unmount).
// `connection` is the WebSocket manager (subscribe + onConnected).
export function startNotifications(connection) {
  refreshUnreadCount() // right after login
  refreshPreferences()
  const unsubscribers = [
    connection.subscribe('/user/queue/notifications', handleNotificationEvent),
    // events may have been missed while the connection was down
    connection.onConnected(() => refreshUnreadCount()),
  ]
  return () => {
    unsubscribers.forEach((unsubscribe) => unsubscribe())
    if (reconcileTimer) clearTimeout(reconcileTimer)
    reconcileTimer = null
  }
}
