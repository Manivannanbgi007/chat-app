import { Lock, MessageCircle, Users, Video } from 'lucide-react'
import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import ChatWindow from '../../components/chat/ChatWindow'
import ConversationList from '../../components/chat/ConversationList'
import { refreshConversationList } from '../../features/messaging/chatActions'
import { toAppError } from '../../services/api/appError'
import { useChatStore } from '../../store/chatStore'

// /chats (the list) and /chats/:conversationId (list + open chat).
// Big screen: the list on the left, the open chat takes ALL the remaining width. Phone: only one of them at a time.
export default function ChatsPage() {
  const { conversationId } = useParams()
  const [loadError, setLoadError] = useState<string | null>(null)

  // Load the list the first time. (After that the socket keeps it up to date.)
  useEffect(() => {
    if (useChatStore.getState().conversationsLoaded) return
    refreshConversationList().catch((error) => setLoadError(toAppError(error).message))
  }, [])

  return (
    <div className="flex h-full min-h-0 overflow-hidden">
      <aside
        className={`${conversationId ? 'hidden md:flex' : 'flex'} min-h-0 w-full flex-col border-r border-gray-200/80 bg-white md:w-[22rem] md:shrink-0 xl:w-[26rem] 2xl:w-[30rem] dark:border-gray-800 dark:bg-gray-900`}
      >
        <ConversationList loadError={loadError} />
      </aside>

      <section className={`${conversationId ? 'flex' : 'hidden md:flex'} min-h-0 min-w-0 flex-1 flex-col`}>
        {conversationId ? (
          <ChatWindow key={conversationId} conversationId={conversationId} />
        ) : (
          <div className="chat-wallpaper flex flex-1 flex-col items-center justify-center gap-4 p-8 text-center">
            <div className="animate-float">
              <span className="btn-brand flex h-24 w-24 items-center justify-center rounded-[2rem] text-white">
                <MessageCircle size={46} strokeWidth={2.2} />
              </span>
            </div>
            <h2 className="animate-fade-up text-2xl font-bold tracking-tight">Select a chat to start messaging</h2>
            <p className="max-w-sm animate-fade-up text-gray-500 [animation-delay:80ms] dark:text-gray-400">
              Pick a conversation on the left, or find someone in your contacts to say hello.
            </p>
            <div className="mt-2 flex animate-fade-up flex-wrap justify-center gap-2 [animation-delay:160ms]">
              <Link to="/contacts" className="flex items-center gap-2 rounded-xl border border-gray-200 bg-white px-4 py-2 text-sm font-medium shadow-sm hover:border-brand-300 hover:text-brand-700 dark:border-gray-700 dark:bg-gray-900 dark:hover:text-brand-300">
                <Users size={16} aria-hidden="true" /> Browse contacts
              </Link>
              <Link to="/calls" className="flex items-center gap-2 rounded-xl border border-gray-200 bg-white px-4 py-2 text-sm font-medium shadow-sm hover:border-brand-300 hover:text-brand-700 dark:border-gray-700 dark:bg-gray-900 dark:hover:text-brand-300">
                <Video size={16} aria-hidden="true" /> Call history
              </Link>
            </div>
            <p className="mt-6 flex items-center gap-1.5 text-xs text-gray-400">
              <Lock size={12} aria-hidden="true" /> Messages are encrypted in transit
            </p>
          </div>
        )}
      </section>
    </div>
  )
}
