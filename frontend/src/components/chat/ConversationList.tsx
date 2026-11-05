import { Archive, ArrowLeft, BellOff, FileText, Image as ImageIcon, Mic, Music, Pin, Users, Video, X } from 'lucide-react'
import { useState } from 'react'
import { Link, NavLink } from 'react-router-dom'
import ChatOptionsMenu from './ChatOptionsMenu'
import ConversationAvatar from './ConversationAvatar'
import NewGroupDialog from './NewGroupDialog'
import StatusTicks from './StatusTicks'
import { arrangeChatList, formatListTime } from '../../features/messaging/conversationList'
import { conversationTitle, currentName, isMuted, isPinned } from '../../features/messaging/conversationInfo'
import { displayStatus } from '../../features/messaging/messageMerge'
import { previewText } from '../../features/messaging/systemMessages'
import { useAuthStore } from '../../store/authStore'
import { useChatStore } from '../../store/chatStore'
import type { Conversation, MediaKind } from '../../types/chat'

// The chat list on the left: pinned chats first, then newest activity first, with the last message and an
// unread badge. Archived chats sit in their own folder ("Archived (n)" row). "New group" starts a group.
export default function ConversationList({ loadError }: { loadError: string | null }) {
  const meId = useAuthStore((state) => state.user?.id) ?? ''
  const conversations = useChatStore((state) => state.conversations)
  const summaries = useChatStore((state) => state.summaries)
  const loaded = useChatStore((state) => state.conversationsLoaded)

  const [showArchived, setShowArchived] = useState(false)
  const [creatingGroup, setCreatingGroup] = useState(false)
  const [actionError, setActionError] = useState<string | null>(null)

  const all = Object.values(conversations)
  const { main, archived } = arrangeChatList(all, summaries)
  const shown = showArchived ? archived : main
  const archivedUnread = archived.reduce((total, conversation) => total + (summaries[conversation.id]?.unreadCount ?? 0), 0)

  return (
    <div className="flex min-h-0 flex-1 flex-col">
      <div className="flex items-center gap-2 px-5 pb-2 pt-5">
        {showArchived ? (
          <>
            <button
              type="button"
              onClick={() => setShowArchived(false)}
              aria-label="Back to chats"
              className="rounded p-1 hover:bg-gray-100 dark:hover:bg-gray-800"
            >
              <ArrowLeft size={20} />
            </button>
            <h1 className="flex-1 py-1 text-2xl font-bold tracking-tight">Archived</h1>
          </>
        ) : (
          <>
            <h1 className="flex-1 py-1 text-2xl font-bold tracking-tight">Chats</h1>
            <button
              type="button"
              onClick={() => setCreatingGroup(true)}
              className="flex items-center gap-1.5 rounded-xl bg-brand-50 px-3 py-2 text-sm font-semibold text-brand-700 hover:bg-brand-100 dark:bg-brand-950/60 dark:text-brand-300 dark:hover:bg-brand-900/60"
            >
              <Users size={18} /> New group
            </button>
          </>
        )}
      </div>

      {loadError && (
        <p role="alert" className="p-4 text-sm text-red-600">
          {loadError}
        </p>
      )}
      {actionError && (
        <div role="alert" className="flex items-start gap-2 bg-red-50 px-4 py-2 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">
          <span className="flex-1">{actionError}</span>
          <button type="button" onClick={() => setActionError(null)} aria-label="Dismiss" className="rounded hover:bg-red-100 dark:hover:bg-red-900">
            <X size={16} />
          </button>
        </div>
      )}
      {!loaded && !loadError && (
        <div className="space-y-1 px-3 py-2" role="status" aria-label="Loading chats">
          <p className="sr-only">Loading chats...</p>
          {[0, 1, 2, 3, 4].map((row) => (
            <div key={row} className="flex items-center gap-3 rounded-2xl p-3" aria-hidden="true">
              <span className="skeleton h-12 w-12 rounded-full" />
              <span className="flex-1 space-y-2">
                <span className="skeleton block h-3 w-1/2 rounded" />
                <span className="skeleton block h-3 w-4/5 rounded" />
              </span>
            </div>
          ))}
        </div>
      )}
      {loaded && all.length === 0 && (
        <p className="p-4 text-sm text-gray-600 dark:text-gray-300">
          No chats yet. Find someone in your{' '}
          <Link to="/contacts" className="text-brand-600 hover:underline">
            contacts
          </Link>{' '}
          and start a conversation, or create a group.
        </p>
      )}
      {loaded && showArchived && archived.length === 0 && (
        <p className="p-4 text-sm text-gray-600 dark:text-gray-300">No archived chats.</p>
      )}

      <ul className="stagger min-h-0 flex-1 space-y-0.5 overflow-y-auto px-2 pb-3 pt-1" aria-label={showArchived ? 'Archived conversations' : 'Conversations'}>
        {!showArchived && archived.length > 0 && (
          <li>
            <button
              type="button"
              onClick={() => setShowArchived(true)}
              className="flex w-full items-center gap-3 rounded-2xl px-3 py-3 text-left hover:bg-gray-50 dark:hover:bg-gray-800/70"
            >
              <Archive size={20} className="text-brand-600" />
              <span className="flex-1 font-medium">Archived ({archived.length})</span>
              {archivedUnread > 0 && (
                <span className="text-xs text-brand-600" aria-label={`${archivedUnread} unread in archived chats`}>
                  {archivedUnread}
                </span>
              )}
            </button>
          </li>
        )}
        {shown.map((conversation) => (
          <ConversationRow key={conversation.id} conversation={conversation} meId={meId} onError={setActionError} />
        ))}
      </ul>

      {creatingGroup && <NewGroupDialog onClose={() => setCreatingGroup(false)} />}
    </div>
  )
}

// The small icon in front of the preview of a media message ("Photo", "Voice message"...).
const PREVIEW_ICONS = { IMAGE: ImageIcon, VIDEO: Video, AUDIO: Music, VOICE: Mic, DOCUMENT: FileText }

function PreviewIcon({ kind }: { kind: MediaKind }) {
  const Icon = PREVIEW_ICONS[kind]
  return <Icon size={14} aria-hidden="true" className="shrink-0" />
}

function ConversationRow({
  conversation,
  meId,
  onError,
}: {
  conversation: Conversation
  meId: string
  onError: (message: string) => void
}) {
  const summary = useChatStore((state) => state.summaries[conversation.id])
  const last = summary?.lastMessage ?? null
  const unread = summary?.unreadCount ?? 0
  const muted = isMuted(conversation)
  const mine = last?.senderId === meId
  const status = last && mine && last.type !== 'SYSTEM' ? displayStatus(last) : null
  const time = last?.createdAt ?? conversation.lastMessageAt
  // In a group, other people's messages show who wrote them: "Ravi: see you".
  const senderPrefix =
    last && conversation.type === 'GROUP' && last.type !== 'SYSTEM' && !mine
      ? `${currentName(conversation, last.senderId) ?? 'Someone'}: `
      : ''

  return (
    <li className="group/row flex animate-fade-up items-center rounded-2xl transition-colors">
      <NavLink
        to={`/chats/${conversation.id}`}
        className={({ isActive }) =>
          `flex min-w-0 flex-1 items-center gap-3 rounded-2xl py-3 pl-3 pr-1 transition-colors ${
            isActive ? 'bg-brand-50 shadow-sm dark:bg-brand-950/50' : 'hover:bg-gray-50 dark:hover:bg-gray-800/70'
          }`
        }
      >
        <ConversationAvatar conversation={conversation} />
        <span className="min-w-0 flex-1">
          <span className="flex items-baseline justify-between gap-2">
            <span className="truncate font-semibold">{conversationTitle(conversation)}</span>
            {time && (
              <span className={`shrink-0 text-xs ${unread > 0 && !muted ? 'font-semibold text-brand-600 dark:text-brand-300' : 'text-gray-500'}`}>
                {formatListTime(time)}
              </span>
            )}
          </span>
          <span className="flex items-center justify-between gap-2">
            <span className="flex min-w-0 items-center gap-1 text-sm text-gray-600 dark:text-gray-300">
              {status && <StatusTicks status={status} />}
              {last && last.type !== 'TEXT' && last.type !== 'SYSTEM' && <PreviewIcon kind={last.type} />}
              <span className="truncate">{last ? senderPrefix + previewText(last, conversation, meId) : 'No messages yet'}</span>
            </span>
            <span className="flex shrink-0 items-center gap-1 text-gray-400">
              {muted && <BellOff size={14} role="img" aria-label="Muted" />}
              {isPinned(conversation) && <Pin size={14} role="img" aria-label="Pinned" />}
              {unread > 0 && (
                <span
                  aria-label={`${unread} unread messages`}
                  key={unread}
                  className={`min-w-5 animate-badge rounded-full px-1.5 py-0.5 text-center text-xs font-semibold leading-none text-white ${
                    muted ? 'bg-gray-400 dark:bg-gray-500' : 'bg-brand-600 shadow-glow'
                  }`}
                >
                  {unread}
                </span>
              )}
            </span>
          </span>
        </span>
      </NavLink>
      <div className="px-1 opacity-60 transition-opacity group-hover/row:opacity-100 focus-within:opacity-100">
        <ChatOptionsMenu conversation={conversation} onError={onError} />
      </div>
    </li>
  )
}
