// Temporary links (blob: URLs) to files this user is sending from this computer.
// They let the sender see their own picture / player at once, and keep showing it after the server has
// accepted the message, so the picture does not flicker while the signed link is being fetched.
// Only kept in memory; the browser forgets them when the page closes.
const previewsByMessageId = new Map<string, string>()

export function rememberPreview(messageId: string, url: string): void {
  previewsByMessageId.set(messageId, url)
}

export function getLocalPreview(messageId: string): string | undefined {
  return previewsByMessageId.get(messageId)
}

// Frees every link (used on logout).
export function forgetAllPreviews(): void {
  for (const url of previewsByMessageId.values()) URL.revokeObjectURL(url)
  previewsByMessageId.clear()
}
