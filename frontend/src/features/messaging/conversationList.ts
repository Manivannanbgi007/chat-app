import type { ChatMessage, Conversation, ConversationSummary } from '../../types/chat'
import { toMicros } from './messageMerge'

export type SummaryState = Pick<ConversationSummary, 'unreadCount'> & { lastMessage: ChatMessage | null }

// The time that decides where a conversation sits in the list: its last message, else the last
// activity the server knows about, else when it was created.
function activityTime(conversation: Conversation, summary?: SummaryState): number {
  const iso = summary?.lastMessage?.createdAt ?? conversation.lastMessageAt ?? conversation.createdAt
  return toMicros(iso)
}

// Newest activity first.
export function sortConversations(
  conversations: Conversation[],
  summaries: Record<string, SummaryState | undefined>,
): Conversation[] {
  return [...conversations].sort((a, b) => activityTime(b, summaries[b.id]) - activityTime(a, summaries[a.id]))
}

// The chat list as it is shown: pinned chats first, then the rest, each part newest activity first.
// Archived chats are kept apart (they live in the "Archived" folder).
export function arrangeChatList(
  conversations: Conversation[],
  summaries: Record<string, SummaryState | undefined>,
): { main: Conversation[]; archived: Conversation[] } {
  const sorted = sortConversations(conversations, summaries)
  const archived = sorted.filter((conversation) => conversation.preferences?.archived)
  const notArchived = sorted.filter((conversation) => !conversation.preferences?.archived)
  const pinned = notArchived.filter((conversation) => conversation.preferences?.pinned)
  const others = notArchived.filter((conversation) => !conversation.preferences?.pinned)
  return { main: [...pinned, ...others], archived }
}

// "10:42" for today, "Yesterday", or "04/11/2026" for older messages.
export function formatListTime(iso: string, now: Date = new Date()): string {
  const date = new Date(iso)
  if (date.toDateString() === now.toDateString()) {
    return date.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
  }
  const yesterday = new Date(now)
  yesterday.setDate(now.getDate() - 1)
  if (date.toDateString() === yesterday.toDateString()) {
    return 'Yesterday'
  }
  return date.toLocaleDateString()
}

// "Today", "Yesterday" or the full date: the label of a date separator inside a chat.
export function formatDayLabel(iso: string, now: Date = new Date()): string {
  const date = new Date(iso)
  if (date.toDateString() === now.toDateString()) return 'Today'
  const yesterday = new Date(now)
  yesterday.setDate(now.getDate() - 1)
  if (date.toDateString() === yesterday.toDateString()) return 'Yesterday'
  return date.toLocaleDateString(undefined, { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' })
}

// "10:42" for today, otherwise "Yesterday, 10:42" or "04/11/2026, 10:42" (used in message info).
export function formatDateTime(iso: string, now: Date = new Date()): string {
  const day = formatListTime(iso, now)
  const clock = formatClock(iso)
  return day === clock ? clock : `${day}, ${clock}`
}

export function formatClock(iso: string): string {
  return new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}
