import { create } from 'zustand'
import type { AppNotification, NotificationPage, NotificationPreferences } from '../types/notification'

// All notification data in memory: the unread count (the bell badge) and the list of notifications.
// The functions here only change data; the code that talks to the server is in features/notifications.

interface NotificationState {
  unreadCount: number
  items: AppNotification[] // newest activity first
  page: number // the last page we loaded (0 = first)
  hasMore: boolean
  loaded: boolean // the first page was fetched
  loading: boolean
  // The switches of Settings > Notifications. null = not known yet. ONE copy that the bell panel, Settings and the
  // desktop pop-ups all read, so they can never disagree.
  preferences: NotificationPreferences | null

  reset: () => void
  setUnreadCount: (count: number) => void
  setPreferences: (preferences: NotificationPreferences | null) => void
  setLoading: (loading: boolean) => void
  setFirstPage: (page: NotificationPage) => void
  appendPage: (page: NotificationPage) => void
  applyLive: (notification: AppNotification) => void
  markReadLocal: (id: string) => void
  markAllReadLocal: () => void
}

const initialState = { unreadCount: 0, items: [] as AppNotification[], page: 0, hasMore: false, loaded: false, loading: false, preferences: null as NotificationPreferences | null }

// Newest first (by the time of the last change). Notifications with the same time keep their order.
function sortNewestFirst(items: AppNotification[]): AppNotification[] {
  return [...items].sort((a, b) => Date.parse(b.updatedAt) - Date.parse(a.updatedAt))
}

// Adds or replaces by id: a notification that is already in the list is REPLACED (so its count grows in place).
function mergeById(current: AppNotification[], incoming: AppNotification[]): AppNotification[] {
  const byId = new Map(current.map((item) => [item.id, item]))
  for (const item of incoming) byId.set(item.id, item)
  return sortNewestFirst([...byId.values()])
}

export const useNotificationStore = create<NotificationState>((set) => ({
  ...initialState,

  // Called on logout so the next user never sees someone else's notifications.
  reset: () => set({ ...initialState }),

  setUnreadCount: (count) => set({ unreadCount: Math.max(0, count) }),

  setPreferences: (preferences) => set({ preferences }),

  setLoading: (loading) => set({ loading }),

  setFirstPage: (page) =>
    set((state) => ({
      // notifications that arrived live while the page was loading stay in the list
      items: mergeById(state.items, page.items),
      page: page.page,
      hasMore: page.hasMore,
      loaded: true,
      loading: false,
    })),

  appendPage: (page) =>
    set((state) => ({
      items: mergeById(state.items, page.items),
      page: page.page,
      hasMore: page.hasMore,
      loading: false,
    })),

  // A notification arrived live (a new one, or an existing one whose count grew).
  applyLive: (notification) =>
    set((state) => {
      const known = state.items.find((item) => item.id === notification.id)
      let unreadCount = state.unreadCount
      if (known) {
        // We know it: adjust only if it changed between read and unread.
        if (known.read && !notification.read) unreadCount += 1
        if (!known.read && notification.read) unreadCount -= 1
      } else if (!notification.read && notification.count <= 1) {
        // A brand-new unread notification. (count > 1 means "an unread one grew": it is already in the badge.)
        unreadCount += 1
      }
      return { items: mergeById(state.items, [notification]), unreadCount: Math.max(0, unreadCount) }
    }),

  markReadLocal: (id) =>
    set((state) => {
      const known = state.items.find((item) => item.id === id)
      if (!known || known.read) return state
      return {
        items: state.items.map((item) => (item.id === id ? { ...item, read: true } : item)),
        unreadCount: Math.max(0, state.unreadCount - 1),
      }
    }),

  markAllReadLocal: () =>
    set((state) => ({ items: state.items.map((item) => ({ ...item, read: true })), unreadCount: 0 })),
}))
