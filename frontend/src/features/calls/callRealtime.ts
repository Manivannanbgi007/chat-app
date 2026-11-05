// Connects the call engine to the WebSocket and to the page's life cycle.
//
// - call events arrive on /user/queue/calls of the app's ONE socket (WEBSOCKET_DESIGN.md section 9)
// - after every (re)connect, and so also on app start / after a reload, we ask the server which call we are in
// - when the page closes during a call, the call is ended on the server (fetch with keepalive)
import { callEngine, type CallEngine } from './callEngine'

// The part of the WebSocket manager this file needs (tests pass a fake).
export interface CallConnection {
  subscribe(destination: string, handler: (event: unknown) => void): () => void
  onConnected(listener: () => void): () => void
}

export const CALLS_DESTINATION = '/user/queue/calls'

// Starts listening. Returns the function that stops everything (logout / unmount).
export function startCalls(connection: CallConnection, engine: CallEngine = callEngine): () => void {
  const unsubscribers = [
    connection.subscribe(CALLS_DESTINATION, (event) => engine.handleEvent(event)),
    // events may have been missed while the connection was down
    connection.onConnected(() => void engine.syncActiveCall()),
  ]
  // Right away too: after a reload this ends the call the page had (its connection is gone) or shows a ringing call.
  void engine.syncActiveCall()

  const onPageHide = () => engine.onPageHide()
  window.addEventListener('pagehide', onPageHide)

  return () => {
    unsubscribers.forEach((unsubscribe) => unsubscribe())
    window.removeEventListener('pagehide', onPageHide)
    engine.stop()
  }
}

// Logout: hang up a running call while the access token still exists (the logout clears it right afterwards).
export function endCallBeforeLogout(engine: CallEngine = callEngine): void {
  engine.stop()
}
