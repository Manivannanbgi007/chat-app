import DropdownMenu, { type MenuItem } from '../common/DropdownMenu'
import { describeGroupError, setArchived, setMuted, setPinned } from '../../features/messaging/chatActions'
import { conversationTitle, isArchived, isMuted, isPinned } from '../../features/messaging/conversationInfo'
import type { Conversation } from '../../types/chat'

// My settings for one chat: pin, mute, archive. Used in the chat list (one per row) and in the chat header.
// Every choice calls the server; the answer (the updated conversation) is stored, so the list re-sorts by itself.
export default function ChatOptionsMenu({
  conversation,
  onError,
}: {
  conversation: Conversation
  onError: (message: string) => void
}) {
  const id = conversation.id

  function run(action: () => Promise<unknown>) {
    action().catch((error: unknown) => onError(describeGroupError(error)))
  }

  const items: MenuItem[] = [
    isPinned(conversation)
      ? { label: 'Unpin chat', onSelect: () => run(() => setPinned(id, false)) }
      : { label: 'Pin chat', onSelect: () => run(() => setPinned(id, true)) },
    ...(isMuted(conversation)
      ? [{ label: 'Unmute', onSelect: () => run(() => setMuted(id, null)) }]
      : [
          { label: 'Mute for 8 hours', onSelect: () => run(() => setMuted(id, 'EIGHT_HOURS')) },
          { label: 'Mute for 1 week', onSelect: () => run(() => setMuted(id, 'ONE_WEEK')) },
          { label: 'Mute always', onSelect: () => run(() => setMuted(id, 'ALWAYS')) },
        ]),
    isArchived(conversation)
      ? { label: 'Unarchive chat', onSelect: () => run(() => setArchived(id, false)) }
      : { label: 'Archive chat', onSelect: () => run(() => setArchived(id, true)) },
  ]

  return <DropdownMenu label={`Chat options for ${conversationTitle(conversation)}`} items={items} />
}
