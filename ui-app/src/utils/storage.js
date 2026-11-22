// localStorage can throw (private window, blocked site data), so every access is wrapped.
export function readStorage(key) {
  try {
    return localStorage.getItem(key)
  } catch {
    return null
  }
}

export function writeStorage(key, value) {
  try {
    localStorage.setItem(key, value)
  } catch {
    // Ignore: the app still works, the choice just is not remembered.
  }
}
