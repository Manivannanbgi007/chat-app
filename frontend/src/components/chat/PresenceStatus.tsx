import { presenceLine } from '../../features/presence/formatPresence'
import { usePresence } from '../../features/presence/usePresence'

// "online" (green dot) or "last seen today at 10:32" under the name of the other person in a PRIVATE chat.
// Shows nothing when the person's privacy settings hide it, or when they have never been seen.
export default function PresenceStatus({ userId }: { userId: string | undefined }) {
  const presence = usePresence(userId)
  const line = presenceLine(presence)

  if (line.kind === 'none') return null
  if (line.kind === 'online') {
    return (
      <span className="inline-flex items-center gap-1.5 font-medium text-emerald-600 dark:text-emerald-400" data-testid="presence">
        <span aria-hidden="true" className="relative flex h-2 w-2">
          <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-emerald-400 opacity-70" />
          <span className="relative inline-flex h-2 w-2 rounded-full bg-emerald-500" />
        </span>
        online
      </span>
    )
  }
  return <span data-testid="presence">{line.text}</span>
}
