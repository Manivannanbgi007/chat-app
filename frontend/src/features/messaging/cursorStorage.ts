// The sync cursor ("I have everything up to here") is remembered per user in localStorage,
// so after a page reload or a long offline time we only fetch what is new.
// Storage can throw (private window, blocked site data), so every access is wrapped.

const PREFIX = 'chat-app-sync-cursor:'

export function getSyncCursor(userId: string): string | undefined {
  try {
    return localStorage.getItem(PREFIX + userId) ?? undefined
  } catch {
    return undefined
  }
}

export function saveSyncCursor(userId: string, cursor: string): void {
  try {
    localStorage.setItem(PREFIX + userId, cursor)
  } catch {
    // Ignore: next time we simply start from "now" again.
  }
}
