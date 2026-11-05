// Where the refresh token lives between page loads.
// - "Remember me" ticked  -> localStorage (survives closing the browser)
// - "Remember me" not set -> sessionStorage (gone when the tab closes)
// The access token is never stored; it only lives in memory (authStore).
// Storage can throw (private window, blocked site data), so every access is wrapped.

const KEY = 'chat-app-refresh-token'

function read(storage: () => Storage): string | null {
  try {
    return storage().getItem(KEY)
  } catch {
    return null
  }
}

function write(storage: () => Storage, value: string): void {
  try {
    storage().setItem(KEY, value)
  } catch {
    // Ignore: the user simply has to log in again next time.
  }
}

function remove(storage: () => Storage): void {
  try {
    storage().removeItem(KEY)
  } catch {
    // Ignore
  }
}

export function getRefreshToken(): string | null {
  return read(() => localStorage) ?? read(() => sessionStorage)
}

// remember = true/false when the user just logged in.
// remember = undefined after a refresh: keep the token wherever it already was.
export function saveRefreshToken(token: string, remember?: boolean): void {
  const useLocal = remember ?? read(() => localStorage) !== null
  remove(() => localStorage)
  remove(() => sessionStorage)
  write(useLocal ? () => localStorage : () => sessionStorage, token)
}

export function clearRefreshToken(): void {
  remove(() => localStorage)
  remove(() => sessionStorage)
}
