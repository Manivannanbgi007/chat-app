// localStorage can throw (private window, blocked site data), so every access is wrapped.
export function readStorage(key: string): string | null {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}

export function writeStorage(key: string, value: string): void {
  try {
    localStorage.setItem(key, value)
  } catch {
    // Ignore: the app still works, the choice just is not remembered.
  }
}
