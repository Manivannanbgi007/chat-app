import { useEffect } from 'react'
import { webSocketManager } from '../../services/websocket/websocketManager'
import { useChatStore } from '../../store/chatStore'
import { useNotificationStore } from '../../store/notificationStore'
import { startCalls } from '../calls/callRealtime'
import { startNotifications } from '../notifications/notificationActions'
import { startRealtime } from './realtime'

// Keeps the live connection open while a logged-in page is shown (the layout of all logged-in pages calls this).
// Logging out unmounts the layout, which closes the connection and forgets all chat data.
export function useRealtime(): void {
  useEffect(() => {
    webSocketManager.start()
    const stopListening = startRealtime(webSocketManager)
    const stopNotifications = startNotifications(webSocketManager) // the bell: unread count + live events
    const stopCalls = startCalls(webSocketManager) // calls: /user/queue/calls + "which call am I in?" after reconnects
    return () => {
      stopListening()
      stopNotifications()
      stopCalls()
      webSocketManager.stop()
      useChatStore.getState().reset()
      useNotificationStore.getState().reset()
    }
  }, [])
}
