import { Bell, BellOff, BellRing, ChevronRight, Loader2, MessageCircle, PhoneMissed, Settings, ShieldCheck, UserMinus, UserPlus } from 'lucide-react'
import { useEffect, useState, type ComponentType } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  loadFirstNotifications,
  loadMoreNotifications,
  markAllNotificationsRead,
  openNotification,
} from '../../features/notifications/notificationActions'
import { toAppError } from '../../services/api/appError'
import { useNotificationStore } from '../../store/notificationStore'
import type { AppNotification, NotificationType } from '../../types/notification'
import { relativeTime } from '../../utils/relativeTime'

// Icon and colours of each kind of notification (full class names, so Tailwind can see them).
const KINDS: Record<NotificationType, { Icon: ComponentType<{ size?: number; className?: string }>; tile: string }> = {
  NEW_MESSAGES: { Icon: MessageCircle, tile: 'bg-brand-100 text-brand-600 dark:bg-brand-950 dark:text-brand-300' },
  GROUP_ADDED: { Icon: UserPlus, tile: 'bg-emerald-100 text-emerald-600 dark:bg-emerald-950 dark:text-emerald-300' },
  GROUP_REMOVED: { Icon: UserMinus, tile: 'bg-amber-100 text-amber-600 dark:bg-amber-950 dark:text-amber-300' },
  GROUP_ADMIN: { Icon: ShieldCheck, tile: 'bg-sky-100 text-sky-600 dark:bg-sky-950 dark:text-sky-300' },
  MISSED_CALL: { Icon: PhoneMissed, tile: 'bg-red-100 text-red-600 dark:bg-red-950 dark:text-red-300' },
}

// The list of notifications (used inside the bell's panel and on the /notifications page).
// ONE state is shown at a time: loading, an error, "switched off", "all caught up", or the list.
// Newest first, "New" above "Earlier". A click marks the notification read and ALWAYS goes somewhere: its chat, the
// Calls page (missed call) or the chat list. "Mark all as read" and a settings shortcut sit in the header.
export default function NotificationList({ onNavigate, showTitle = false }: { onNavigate?: () => void; showTitle?: boolean }) {
  const navigate = useNavigate()
  const items = useNotificationStore((state) => state.items)
  const loaded = useNotificationStore((state) => state.loaded)
  const loading = useNotificationStore((state) => state.loading)
  const hasMore = useNotificationStore((state) => state.hasMore)
  const unreadCount = useNotificationStore((state) => state.unreadCount)
  const preferences = useNotificationStore((state) => state.preferences)
  const [error, setError] = useState<string | null>(null)

  // Fresh list every time it is shown.
  useEffect(() => {
    loadFirstNotifications().catch((caught) => setError(toAppError(caught).message))
  }, [])

  // Every kind of notification is switched off in Settings (nothing new will arrive).
  const allOff = preferences !== null && !preferences.messagesEnabled && !preferences.groupsEnabled && !preferences.callsEnabled

  async function handleOpen(notification: AppNotification) {
    const path = await openNotification(notification)
    onNavigate?.()
    navigate(path)
  }

  function goToSettings() {
    onNavigate?.()
    navigate('/settings#notifications')
  }

  function handleLoadMore() {
    setError(null)
    loadMoreNotifications().catch((caught) => setError(toAppError(caught).message))
  }

  const fresh = items.filter((item) => !item.read)
  const earlier = items.filter((item) => item.read)
  const showEmpty = loaded && items.length === 0 && !error
  const showLoading = !loaded && loading && !error

  return (
    <div className="flex min-h-0 flex-col">
      {/* header */}
      <div className="flex items-center gap-2 px-4 pb-2 pt-4">
        <div className="min-w-0 flex-1">
          {showTitle && <h2 className="text-lg font-bold tracking-tight">Notifications</h2>}
          {!showEmpty && !showLoading && !error && (
            <p className={`text-sm ${unreadCount > 0 ? 'font-medium text-brand-700 dark:text-brand-300' : 'text-gray-500 dark:text-gray-400'}`}>
              {unreadCount > 0 ? `${unreadCount} unread` : 'Nothing new'}
            </p>
          )}
        </div>
        {unreadCount > 0 && (
          <button
            type="button"
            onClick={() => void markAllNotificationsRead()}
            className="rounded-lg px-2.5 py-1.5 text-sm font-medium text-brand-700 hover:bg-brand-50 dark:text-brand-300 dark:hover:bg-brand-950/60"
          >
            Mark all as read
          </button>
        )}
        <button
          type="button"
          onClick={goToSettings}
          aria-label="Notification settings"
          title="Notification settings"
          className="rounded-lg p-2 text-gray-500 hover:bg-gray-100 hover:text-gray-900 dark:text-gray-400 dark:hover:bg-gray-800 dark:hover:text-gray-100"
        >
          <Settings size={18} />
        </button>
      </div>

      {error && (
        <p role="alert" className="mx-4 mb-3 rounded-xl bg-red-50 px-3 py-2 text-sm text-red-700 dark:bg-red-950/60 dark:text-red-300">
          {error}
        </p>
      )}

      {/* ONE state: loading */}
      {showLoading && (
        <div className="space-y-1 px-2 pb-3" role="status" aria-label="Loading notifications">
          <p className="sr-only">Loading...</p>
          {[0, 1, 2].map((row) => (
            <div key={row} className="flex items-center gap-3 rounded-xl p-3" aria-hidden="true">
              <span className="skeleton h-10 w-10 rounded-full" />
              <span className="flex-1 space-y-2">
                <span className="skeleton block h-3 w-1/2 rounded" />
                <span className="skeleton block h-3 w-3/4 rounded" />
              </span>
            </div>
          ))}
        </div>
      )}

      {/* ONE state: nothing to show - either switched off, or all caught up */}
      {showEmpty && allOff && (
        <div className="flex flex-col items-center gap-3 px-6 pb-8 pt-4 text-center">
          <span className="flex h-14 w-14 items-center justify-center rounded-full bg-gray-100 text-gray-500 dark:bg-gray-800 dark:text-gray-400">
            <BellOff size={26} />
          </span>
          <div>
            <p className="font-semibold">Notifications are turned off</p>
            <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">You will not be told about new messages, groups or missed calls.</p>
          </div>
          <button
            type="button"
            onClick={goToSettings}
            className="btn-brand rounded-xl px-4 py-2 text-sm font-semibold text-white"
          >
            Turn them on
          </button>
        </div>
      )}
      {showEmpty && !allOff && (
        <div className="flex flex-col items-center gap-3 px-6 pb-8 pt-4 text-center">
          <span className="flex h-14 w-14 items-center justify-center rounded-full bg-brand-50 text-brand-600 dark:bg-brand-950 dark:text-brand-300">
            <BellRing size={26} />
          </span>
          <div>
            <p className="font-semibold">You are all caught up</p>
            <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">New messages, group changes and missed calls will appear here.</p>
          </div>
        </div>
      )}

      {/* old notifications are still listed after everything was switched off: say so once, in one slim line */}
      {allOff && items.length > 0 && (
        <button
          type="button"
          onClick={goToSettings}
          className="mx-4 mb-2 flex items-center gap-2 rounded-xl bg-gray-100 px-3 py-2 text-left text-xs text-gray-600 hover:bg-gray-200 dark:bg-gray-800 dark:text-gray-300 dark:hover:bg-gray-700"
        >
          <BellOff size={14} className="shrink-0" />
          <span className="flex-1">Notifications are turned off, so nothing new will arrive.</span>
          <span className="font-semibold text-brand-700 dark:text-brand-300">Turn on</span>
        </button>
      )}

      {/* the list (only when there is something) */}
      {items.length > 0 && (
        <ul aria-label="Notifications" className="stagger min-h-0 overflow-y-auto px-2 pb-2">
          {fresh.length > 0 && earlier.length > 0 && <SectionLabel>New</SectionLabel>}
          {fresh.map((notification) => (
            <Row key={notification.id} notification={notification} onOpen={handleOpen} />
          ))}
          {earlier.length > 0 && fresh.length > 0 && <SectionLabel>Earlier</SectionLabel>}
          {earlier.map((notification) => (
            <Row key={notification.id} notification={notification} onOpen={handleOpen} />
          ))}
        </ul>
      )}

      {hasMore && (
        <button
          type="button"
          onClick={handleLoadMore}
          disabled={loading}
          className="mx-4 mb-4 mt-1 flex items-center justify-center gap-2 rounded-xl border border-gray-200 px-3 py-2 text-sm font-medium text-brand-700 hover:bg-brand-50 disabled:opacity-50 dark:border-gray-700 dark:text-brand-300 dark:hover:bg-brand-950/50"
        >
          {loading && <Loader2 className="animate-spin" size={14} />}
          {loading ? 'Loading...' : 'Load more'}
        </button>
      )}
    </div>
  )
}

function SectionLabel({ children }: { children: string }) {
  return (
    <li aria-hidden="true" className="px-3 pb-1 pt-3 text-[11px] font-semibold uppercase tracking-wider text-gray-400">
      {children}
    </li>
  )
}

// One notification: a coloured icon, who / what and when on the first line, ONE line of text below.
function Row({ notification, onOpen }: { notification: AppNotification; onOpen: (notification: AppNotification) => Promise<void> }) {
  const { Icon, tile } = KINDS[notification.type] ?? { Icon: Bell, tile: 'bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-300' }
  return (
    <li className="animate-fade-up">
      <button
        type="button"
        onClick={() => void onOpen(notification)}
        className={`group flex w-full cursor-pointer items-center gap-3 rounded-xl px-3 py-2.5 text-left transition-colors hover:bg-gray-100 active:bg-gray-200 dark:hover:bg-gray-800 dark:active:bg-gray-700 ${
          notification.read ? '' : 'bg-brand-50/70 dark:bg-brand-950/40'
        }`}
      >
        <span className={`flex h-10 w-10 shrink-0 items-center justify-center rounded-full ${tile}`}>
          <Icon size={18} />
        </span>
        <span className="min-w-0 flex-1">
          <span className="flex items-baseline justify-between gap-2">
            <span className={`truncate ${notification.read ? 'font-medium' : 'font-semibold'}`}>{notification.title}</span>
            <span className="shrink-0 text-xs text-gray-400">{relativeTime(notification.updatedAt)}</span>
          </span>
          <span className={`block truncate text-sm ${notification.read ? 'text-gray-500 dark:text-gray-400' : 'text-gray-700 dark:text-gray-200'}`}>
            {notification.body}
          </span>
        </span>
        {!notification.read ? (
          <span role="img" aria-label="Unread" className="h-2.5 w-2.5 shrink-0 rounded-full bg-brand-500 shadow-glow" />
        ) : (
          <ChevronRight size={16} aria-hidden="true" className="shrink-0 text-gray-300 opacity-0 transition-opacity group-hover:opacity-100" />
        )}
      </button>
    </li>
  )
}
