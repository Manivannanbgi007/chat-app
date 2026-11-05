import { CircleDashed, LogOut, MessageCircle, PanelLeftClose, PanelLeftOpen, Phone, Settings, UserRound, Users, type LucideIcon } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import CallOverlay from '../components/calls/CallOverlay'
import ConnectionBanner from '../components/chat/ConnectionBanner'
import Avatar from '../components/common/Avatar'
import Brand from '../components/common/Brand'
import ThemeToggle from '../components/common/ThemeToggle'
import NotificationBell from '../components/notifications/NotificationBell'
import { logoutUser } from '../features/authentication/authActions'
import { useRealtime } from '../features/messaging/useRealtime'
import { usePresenceHeartbeat } from '../features/presence/usePresenceHeartbeat'
import { useAuthStore } from '../store/authStore'
import { useChatStore } from '../store/chatStore'
import { readStorage, writeStorage } from '../utils/storage'
import { registerNavigator } from '../utils/navigationBridge'

const MENU_KEY = 'chat-app-menu'

// The left menu is open (icons + names) or closed (icons only). The choice is remembered; the first time it is open on
// wide windows and closed on narrower ones.
function loadMenuOpen(): boolean {
  const saved = readStorage(MENU_KEY)
  if (saved === 'open') return true
  if (saved === 'closed') return false
  return typeof window !== 'undefined' && window.innerWidth >= 1536
}

const NAV_ITEMS: { to: string; label: string; Icon: LucideIcon }[] = [
  { to: '/chats', label: 'Chats', Icon: MessageCircle },
  { to: '/status', label: 'Status', Icon: CircleDashed },
  { to: '/calls', label: 'Calls', Icon: Phone },
  { to: '/contacts', label: 'Contacts', Icon: Users },
  { to: '/profile', label: 'Profile', Icon: UserRound },
  { to: '/settings', label: 'Settings', Icon: Settings },
]

// The frame of every logged-in page. It always fills the whole browser window:
//   big screen:  a navigation column on the left that the user can open (icons + names) or close (icons only) with the
//                toggle button, the page fills the rest of the window
//   phone:       a slim top bar, the page, and the navigation as a tab bar at the bottom
// It is ONE navigation and ONE set of buttons that CSS moves around (display: contents), so there are no duplicates.
export default function AppLayout() {
  const user = useAuthStore((state) => state.user)
  const unreadTotal = useChatStore((state) => Object.values(state.summaries).reduce((sum, summary) => sum + summary.unreadCount, 0))
  const navigate = useNavigate()
  const location = useLocation()
  const [menuOpen, setMenuOpen] = useState(loadMenuOpen)

  function toggleMenu() {
    setMenuOpen((open) => {
      writeStorage(MENU_KEY, open ? 'closed' : 'open')
      return !open
    })
  }
  // The chat screens fill the page themselves; the other pages scroll inside the page area.
  const isChat = location.pathname.startsWith('/chats')
  // Changing the section (not a chat inside /chats) plays the page-in animation again.
  const section = location.pathname.split('/')[1] ?? ''

  // Keep the live connection open while any logged-in page is shown.
  useRealtime()
  // Tell the server every 20 seconds that this device is alive (so others see "online").
  usePresenceHeartbeat()

  // Lets code outside React (a click on a desktop notification) open a page without reloading the app.
  useEffect(() => registerNavigator((path) => navigate(path)), [navigate])

  async function handleLogout() {
    await logoutUser()
    navigate('/login', { replace: true })
  }

  return (
    <div className="flex h-dvh w-full flex-col overflow-hidden bg-gray-50 md:flex-row dark:bg-gray-950">
      {/* On a phone this <aside> has no box of its own: its children become rows of the page frame. */}
      <aside
        data-expanded={menuOpen}
        className="group/side contents md:relative md:z-20 md:flex md:w-[4.75rem] md:shrink-0 md:flex-col md:overflow-x-hidden md:border-r md:border-gray-200/80 md:bg-white md:px-3 md:py-4 md:transition-[width] md:duration-300 md:ease-out md:data-[expanded=true]:w-64 dark:md:border-gray-800 dark:md:bg-gray-900"
      >
        <div className="hidden flex-col items-center gap-3 pb-5 md:flex md:group-data-[expanded=true]/side:flex-row md:group-data-[expanded=true]/side:justify-between">
          <Link to="/chats" aria-label="Chat App home" className="flex items-center gap-3 px-0.5">
            <Brand size={40} />
            <span className="hidden whitespace-nowrap text-lg font-bold tracking-tight md:group-data-[expanded=true]/side:inline">Chat App</span>
          </Link>
          <button
            type="button"
            onClick={toggleMenu}
            aria-label={menuOpen ? 'Collapse menu' : 'Expand menu'}
            aria-expanded={menuOpen}
            title={menuOpen ? 'Collapse menu' : 'Expand menu'}
            className="rounded-xl p-2 text-gray-500 hover:bg-gray-100 hover:text-gray-900 dark:text-gray-400 dark:hover:bg-gray-800 dark:hover:text-gray-100"
          >
            <span key={String(menuOpen)} className="block animate-pop">
              {menuOpen ? <PanelLeftClose size={20} aria-hidden="true" /> : <PanelLeftOpen size={20} aria-hidden="true" />}
            </span>
          </button>
        </div>

        <nav
          aria-label="Main"
          className="order-last grid shrink-0 grid-cols-6 border-t border-gray-200/80 bg-white/90 pb-[env(safe-area-inset-bottom)] backdrop-blur md:order-none md:flex md:flex-col md:gap-1 md:border-0 md:bg-transparent md:pb-0 md:backdrop-blur-none dark:border-gray-800 dark:bg-gray-900/90 dark:md:bg-transparent"
        >
          {NAV_ITEMS.map(({ to, label, Icon }) => (
            <NavLink
              key={to}
              to={to}
              title={menuOpen ? undefined : label}
              className={({ isActive }) =>
                `group relative flex flex-col items-center justify-center gap-0.5 px-1 py-2 text-[11px] font-medium transition-colors md:flex-row md:rounded-2xl md:py-2.5 md:group-data-[expanded=true]/side:justify-start md:group-data-[expanded=true]/side:gap-3 md:group-data-[expanded=true]/side:px-4 md:group-data-[expanded=true]/side:text-sm ${
                  isActive
                    ? 'text-brand-600 md:bg-brand-50 dark:text-brand-300 dark:md:bg-brand-950/70'
                    : 'text-gray-500 hover:text-gray-900 md:hover:bg-gray-100 dark:text-gray-400 dark:hover:text-gray-100 dark:md:hover:bg-gray-800'
                }`
              }
            >
              {({ isActive }) => (
                <>
                  <span className={`relative transition-transform duration-200 ${isActive ? 'scale-110' : 'group-hover:scale-110'}`}>
                    <Icon size={22} strokeWidth={isActive ? 2.4 : 2} aria-hidden="true" />
                    {to === '/chats' && unreadTotal > 0 && (
                      <span
                        aria-hidden="true"
                        key={unreadTotal}
                        className="absolute -right-2 -top-1.5 min-w-4 animate-badge rounded-full bg-brand-600 px-1 text-center text-[10px] font-semibold leading-4 text-white shadow-glow"
                      >
                        {unreadTotal > 99 ? '99+' : unreadTotal}
                      </span>
                    )}
                  </span>
                  <span className={`whitespace-nowrap md:sr-only md:group-data-[expanded=true]/side:not-sr-only`}>{label}</span>
                  {isActive && <span aria-hidden="true" className="absolute -left-3 top-1/2 hidden h-6 w-1 -translate-y-1/2 animate-fade-in rounded-r-full bg-brand-600 md:block" />}
                </>
              )}
            </NavLink>
          ))}
        </nav>

        {/* Phone: the top bar. Big screen: the bottom of the navigation column. */}
        <div className="order-first flex shrink-0 items-center justify-between gap-2 border-b border-gray-200/80 bg-white/90 px-3 py-2 backdrop-blur md:order-none md:mt-auto md:flex-col md:items-stretch md:gap-2 md:border-0 md:bg-transparent md:p-0 md:backdrop-blur-none dark:border-gray-800 dark:bg-gray-900/90 dark:md:bg-transparent">
          <Link to="/chats" className="flex items-center gap-2 md:hidden" aria-hidden="true" tabIndex={-1}>
            <Brand size={32} />
            <span className="font-bold tracking-tight">Chat App</span>
          </Link>
          <div className={`flex items-center gap-1 md:flex-col md:group-data-[expanded=true]/side:flex-row`}>
            <NotificationBell />
            <ThemeToggle />
          </div>
          <div className="flex items-center gap-2 md:flex-col md:rounded-2xl md:bg-gray-100/80 md:p-2 md:group-data-[expanded=true]/side:flex-row md:group-data-[expanded=true]/side:p-2.5 dark:md:bg-gray-800/70">
            {user && (
              <span className="flex min-w-0 items-center gap-2.5">
                <Avatar name={user.displayName} size={34} />
                <span className="hidden min-w-0 md:group-data-[expanded=true]/side:block">
                  <span className="block truncate text-sm font-semibold">{user.displayName}</span>
                  <span className="block truncate text-xs text-gray-500 dark:text-gray-400">@{user.username}</span>
                </span>
              </span>
            )}
            <button
              type="button"
              onClick={handleLogout}
              aria-label="Log out"
              title="Log out"
              className="ml-auto flex shrink-0 items-center gap-1 rounded-xl p-2 text-gray-500 hover:bg-gray-200/70 hover:text-red-600 dark:text-gray-400 dark:hover:bg-gray-700 dark:hover:text-red-400"
            >
              <LogOut size={18} aria-hidden="true" />
              <span className="hidden whitespace-nowrap text-sm md:group-data-[expanded=true]/side:inline" aria-hidden="true">
                Log out
              </span>
            </button>
          </div>
        </div>
      </aside>

      <div className="relative flex min-h-0 min-w-0 flex-1 flex-col">
        <ConnectionBanner />
        <main className={`min-h-0 flex-1 ${isChat ? 'overflow-hidden' : 'overflow-y-auto'}`}>
          <div key={section} className={`animate-fade-in ${isChat ? 'h-full' : 'mx-auto min-h-full w-full max-w-[110rem] p-4 md:p-8'}`}>
            <Outlet />
          </div>
        </main>
      </div>
      {/* The call screen lies over every page, so a call can ring and continue wherever I am in the app. */}
      <CallOverlay />
    </div>
  )
}
