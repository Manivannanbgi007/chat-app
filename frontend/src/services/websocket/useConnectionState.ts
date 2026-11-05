import { useSyncExternalStore } from 'react'
import { webSocketManager, type ConnectionState } from './websocketManager'

// The current WebSocket connection state, for the UI.
export function useConnectionState(): ConnectionState {
  return useSyncExternalStore(webSocketManager.onStateChange, webSocketManager.getState)
}
