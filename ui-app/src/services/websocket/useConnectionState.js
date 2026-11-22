import { useSyncExternalStore } from 'react'
import { webSocketManager } from './websocketManager'

// The current WebSocket connection state, for the UI: 'offline' | 'connecting' | 'connected' | 'reconnecting'.
export function useConnectionState() {
  return useSyncExternalStore(webSocketManager.onStateChange, webSocketManager.getState)
}
