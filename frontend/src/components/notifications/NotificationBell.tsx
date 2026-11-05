import { Bell } from 'lucide-react'
import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { useNavigate } from 'react-router-dom'
import { badgeText } from '../../features/notifications/badgeText'
import { useNotificationStore } from '../../store/notificationStore'
import NotificationList from './NotificationList'

// A phone shows the notifications as a full page; a bigger screen shows a panel next to the bell.
function isPhone(): boolean {
  return typeof window.matchMedia === 'function' && window.matchMedia('(max-width: 639px)').matches
}

// Where the panel goes, in window coordinates. It is drawn on top of the whole page (a portal), so no parent can cut it off.
//   big screen: the bell sits in the left menu, so the panel opens to its right, its bottom edge level with the bell
//   in between: the bell is in the top bar, so the panel opens below it
function panelPosition(button: HTMLElement | null): React.CSSProperties {
  if (!button) return { top: 64, right: 12 }
  const rect = button.getBoundingClientRect()
  const maxHeight = Math.max(240, window.innerHeight - 24)
  if (window.innerWidth >= 768) {
    return { left: rect.right + 12, bottom: Math.max(12, window.innerHeight - rect.bottom), maxHeight: Math.min(maxHeight, 560) }
  }
  return { top: rect.bottom + 8, right: 12, maxHeight: Math.min(window.innerHeight - rect.bottom - 20, 560) }
}

// The bell with the unread badge. Closes on a click outside and on Escape.
export default function NotificationBell() {
  const navigate = useNavigate()
  const unreadCount = useNotificationStore((state) => state.unreadCount)
  const [open, setOpen] = useState(false)
  const [position, setPosition] = useState<React.CSSProperties>({})
  const buttonRef = useRef<HTMLButtonElement>(null)
  const panelRef = useRef<HTMLDivElement>(null)

  useLayoutEffect(() => {
    if (!open) return
    const place = () => setPosition(panelPosition(buttonRef.current))
    place()
    window.addEventListener('resize', place)
    return () => window.removeEventListener('resize', place)
  }, [open])

  useEffect(() => {
    if (!open) return
    function onPointerDown(event: MouseEvent) {
      const target = event.target as Node
      if (buttonRef.current?.contains(target) || panelRef.current?.contains(target)) return
      setOpen(false)
    }
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', onPointerDown)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('mousedown', onPointerDown)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [open])

  function handleClick() {
    if (isPhone()) navigate('/notifications')
    else setOpen((value) => !value)
  }

  return (
    <div className="relative">
      <button
        ref={buttonRef}
        type="button"
        onClick={handleClick}
        aria-label={unreadCount > 0 ? `Notifications, ${unreadCount} unread` : 'Notifications'}
        aria-haspopup="dialog"
        aria-expanded={open}
        className={`group relative rounded-xl p-2 hover:bg-gray-100 hover:text-gray-900 dark:hover:bg-gray-800 dark:hover:text-gray-100 ${
          open ? 'bg-brand-50 text-brand-700 dark:bg-brand-950/60 dark:text-brand-300' : 'text-gray-500 dark:text-gray-400'
        }`}
      >
        <Bell size={20} className={`origin-top transition-transform group-hover:rotate-12 ${unreadCount > 0 ? 'text-brand-600 dark:text-brand-300' : ''}`} />
        {unreadCount > 0 && (
          <span
            data-testid="bell-badge"
            aria-hidden="true"
            key={unreadCount}
            className="absolute right-0.5 top-0.5 min-w-4 animate-badge rounded-full bg-red-500 px-1 text-center text-[10px] font-semibold leading-4 text-white ring-2 ring-white dark:ring-gray-900"
          >
            {badgeText(unreadCount)}
          </span>
        )}
      </button>

      {open &&
        createPortal(
          <div
            ref={panelRef}
            role="dialog"
            aria-label="Notifications panel"
            style={position}
            className="fixed z-50 flex w-[26rem] max-w-[calc(100vw-1.5rem)] animate-pop flex-col overflow-hidden rounded-2xl border border-gray-200/80 bg-white shadow-float dark:border-gray-800 dark:bg-gray-900"
          >
            <NotificationList showTitle onNavigate={() => setOpen(false)} />
          </div>,
          document.body,
        )}
    </div>
  )
}
