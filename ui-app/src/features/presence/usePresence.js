import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect } from 'react'
import { getPresence } from '../../services/api/presenceApi'
import { webSocketManager } from '../../services/websocket/websocketManager'

export const PRESENCE_REFRESH_MS = 10_000

// Is this person online? Asked when the chat opens and then every 10 seconds, but only while the tab is visible
// (and once more as soon as it becomes visible again). The old answer stays on screen while a new one loads,
// so the header does not flicker. Returns the Presence (types/presence.js) or undefined while unknown.
export function usePresence(userId) {
  const queryClient = useQueryClient()

  const query = useQuery({
    queryKey: ['presence', userId],
    queryFn: () => getPresence(userId),
    enabled: !!userId,
    staleTime: 5_000,
    refetchInterval: () => (document.visibilityState === 'visible' ? PRESENCE_REFRESH_MS : false),
    refetchOnWindowFocus: true, // also fires when the tab becomes visible again
  })

  // After a WebSocket reconnect the status may have changed while we were away: ask again.
  useEffect(() => {
    if (!userId) return
    return webSocketManager.onConnected(() => {
      queryClient.invalidateQueries({ queryKey: ['presence', userId] })
    })
  }, [userId, queryClient])

  return query.data
}
