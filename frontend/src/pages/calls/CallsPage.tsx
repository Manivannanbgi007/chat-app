import { Loader2, Phone, PhoneIncoming, PhoneMissed, PhoneOutgoing, Video } from 'lucide-react'
import { useEffect, useState } from 'react'
import Avatar from '../../components/common/Avatar'
import { callEngine } from '../../features/calls/callEngine'
import { formatCallTime, historyOutcome, isMissed } from '../../features/calls/callText'
import { isBusyState, useCallStore } from '../../features/calls/callStore'
import PageHeader from '../../components/common/PageHeader'
import { toAppError } from '../../services/api/appError'
import { getCallHistory } from '../../services/api/callApi'
import type { Call } from '../../types/call'

const PAGE_SIZE = 20

// /calls: every call I made or received, newest first. Missed calls are red. Each row can call the person back.
export default function CallsPage() {
  const historyVersion = useCallStore((state) => state.historyVersion)
  const busy = useCallStore((state) => isBusyState(state.state))

  const [items, setItems] = useState<Call[]>([])
  const [page, setPage] = useState(0)
  const [hasMore, setHasMore] = useState(false)
  const [loaded, setLoaded] = useState(false)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // The first page: on opening, and again whenever a call ends (so the call that just finished shows up).
  useEffect(() => {
    let cancelled = false
    getCallHistory(0, PAGE_SIZE)
      .then((result) => {
        if (cancelled) return
        setItems(result.items)
        setPage(0)
        setHasMore(result.hasMore)
        setLoaded(true)
        setError(null)
      })
      .catch((caught) => {
        if (!cancelled) setError(toAppError(caught).message)
      })
    return () => {
      cancelled = true
    }
  }, [historyVersion])

  async function loadMore() {
    setLoading(true)
    setError(null)
    try {
      const result = await getCallHistory(page + 1, PAGE_SIZE)
      // A call that arrived meanwhile shifts the pages: skip rows we already show.
      setItems((current) => [...current, ...result.items.filter((call) => !current.some((known) => known.id === call.id))])
      setPage(result.page)
      setHasMore(result.hasMore)
    } catch (caught) {
      setError(toAppError(caught).message)
    } finally {
      setLoading(false)
    }
  }

  return (
    <section>
      <PageHeader title="Calls" subtitle="Your recent voice and video calls. Tap a button to call back." />

      {error && (
        <p role="alert" className="px-4 py-3 text-sm text-red-600">
          {error}
        </p>
      )}
      {!loaded && !error && (
        <p className="flex items-center gap-2 px-4 py-4 text-sm">
          <Loader2 className="animate-spin" size={18} /> Loading calls...
        </p>
      )}
      {loaded && items.length === 0 && (
        <p className="card flex flex-col items-center gap-3 px-4 py-16 text-center text-sm text-gray-500">
          <Phone size={32} /> No calls yet. Start one from a chat with the phone or camera button.
        </p>
      )}

      <ul aria-label="Call history" className="stagger grid gap-3 xl:grid-cols-2 2xl:grid-cols-3">
        {items.map((call) => {
          const missed = isMissed(call)
          const name = call.otherUser.displayName
          const DirectionIcon = missed ? PhoneMissed : call.direction === 'OUTGOING' ? PhoneOutgoing : PhoneIncoming
          return (
            <li
              key={call.id}
              data-testid="call-row"
              className="card flex animate-fade-up items-center gap-3 p-4 transition duration-200 hover:-translate-y-0.5 hover:shadow-float"
            >
              <Avatar name={name} imageUrl={call.otherUser.profileImageUrl} size={48} />
              <span className="min-w-0 flex-1">
                <span className={`block truncate font-medium ${missed ? 'text-red-600 dark:text-red-400' : ''}`}>{name}</span>
                <span className="flex items-center gap-1 text-sm text-gray-600 dark:text-gray-300">
                  <DirectionIcon
                    size={14}
                    className={missed ? 'text-red-600 dark:text-red-400' : 'text-brand-600'}
                    aria-label={missed ? 'Missed' : call.direction === 'OUTGOING' ? 'Outgoing' : 'Incoming'}
                    role="img"
                  />
                  {call.type === 'VIDEO' && <Video size={14} aria-label="Video call" role="img" />}
                  <span data-testid="call-outcome" className={missed ? 'text-red-600 dark:text-red-400' : ''}>
                    {historyOutcome(call)}
                  </span>
                  <span aria-hidden="true">·</span>
                  <time dateTime={call.createdAt}>{formatCallTime(call.createdAt)}</time>
                </span>
              </span>
              <button
                type="button"
                aria-label={`Voice call ${name}`}
                title="Voice call"
                disabled={busy}
                onClick={() => void callEngine.startCall(call.otherUser, 'AUDIO')}
                className="rounded-xl bg-brand-50 p-2.5 text-brand-600 hover:bg-brand-100 disabled:cursor-not-allowed disabled:opacity-40 dark:bg-brand-950/60 dark:hover:bg-brand-900/60"
              >
                <Phone size={18} />
              </button>
              <button
                type="button"
                aria-label={`Video call ${name}`}
                title="Video call"
                disabled={busy}
                onClick={() => void callEngine.startCall(call.otherUser, 'VIDEO')}
                className="rounded-xl bg-brand-50 p-2.5 text-brand-600 hover:bg-brand-100 disabled:cursor-not-allowed disabled:opacity-40 dark:bg-brand-950/60 dark:hover:bg-brand-900/60"
              >
                <Video size={18} />
              </button>
            </li>
          )
        })}
      </ul>

      {hasMore && (
        <button
          type="button"
          onClick={() => void loadMore()}
          disabled={loading}
          className="mx-auto mt-6 block rounded-xl border border-gray-200 px-5 py-2.5 text-sm font-medium text-brand-700 hover:bg-brand-50 disabled:opacity-50 dark:border-gray-700 dark:text-brand-300 dark:hover:bg-brand-950/50"
        >
          {loading ? 'Loading...' : 'Load more'}
        </button>
      )}
    </section>
  )
}
