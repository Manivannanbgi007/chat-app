import { useEffect } from 'react'
import { sendHeartbeat, sendOffline } from '../../services/api/presenceApi'
import { useAuthStore } from '../../store/authStore'

export const HEARTBEAT_INTERVAL_MS = 20_000

// Tells the server "this device is alive" every 20 seconds, so other people can see that I am online.
//
// - Mounted once, in the layout of all logged-in pages (so never while logged out).
// - Only while the tab is VISIBLE: a hidden tab stops sending and the server turns it offline by itself
//   after 60 seconds. One heartbeat is sent as soon as the tab becomes visible again.
// - A failed heartbeat is ignored: the next one (20 s later) tries again.
// - When the page closes (or is put into the back/forward cache) the device is marked offline at once.
export function usePresenceHeartbeat(): void {
  const loggedIn = useAuthStore((state) => state.accessToken !== null)

  useEffect(() => {
    if (!loggedIn) return

    function beat() {
      if (document.visibilityState !== 'visible') return
      sendHeartbeat().catch(() => undefined)
    }

    beat() // right after login
    const timer = setInterval(beat, HEARTBEAT_INTERVAL_MS)
    document.addEventListener('visibilitychange', beat)
    window.addEventListener('pagehide', sendOffline)

    return () => {
      clearInterval(timer)
      document.removeEventListener('visibilitychange', beat)
      window.removeEventListener('pagehide', sendOffline)
    }
  }, [loggedIn])
}
