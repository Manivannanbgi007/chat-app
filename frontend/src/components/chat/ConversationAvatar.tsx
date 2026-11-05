import { Users } from 'lucide-react'
import Avatar from '../common/Avatar'
import type { Conversation } from '../../types/chat'

// The round picture of a chat: the other person in a private chat, the group's initials in a group
// (a group icon when the name gives no initials). Group pictures arrive with media upload (Phase 5).
export default function ConversationAvatar({ conversation, size = 40 }: { conversation: Conversation; size?: number }) {
  if (conversation.type !== 'GROUP') {
    const other = conversation.otherUser
    return <Avatar name={other?.displayName ?? '?'} imageUrl={other?.profileImageUrl} size={size} />
  }

  const name = conversation.group?.name ?? ''
  if (conversation.group?.imageUrl) {
    return <Avatar name={name} imageUrl={conversation.group.imageUrl} size={size} />
  }
  const initials = name
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map((word) => word[0]?.toUpperCase() ?? '')
    .join('')

  return (
    <span
      aria-hidden="true"
      className="inline-flex shrink-0 items-center justify-center rounded-full bg-sky-100 font-semibold text-sky-700 dark:bg-sky-900 dark:text-sky-200"
      style={{ width: size, height: size, fontSize: size / 2.5 }}
    >
      {initials || <Users size={size / 2} />}
    </span>
  )
}
