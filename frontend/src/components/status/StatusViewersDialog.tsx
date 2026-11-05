import { useQuery } from '@tanstack/react-query'
import { Eye, Loader2, X } from 'lucide-react'
import { useEffect, useRef } from 'react'
import Avatar from '../common/Avatar'
import { formatStatusTime } from '../../features/status/statusRules'
import { toAppError } from '../../services/api/appError'
import { getStatusViewers } from '../../services/api/statusApi'

// Who saw one of MY statuses (asked fresh every time it opens). People who turned read receipts off are not
// listed, and when MY read receipts are off the list is hidden (viewersHidden), as in WhatsApp.
export default function StatusViewersDialog({ statusId, onClose }: { statusId: string; onClose: () => void }) {
  const closeRef = useRef<HTMLButtonElement>(null)
  const query = useQuery({
    queryKey: ['status-viewers', statusId],
    queryFn: () => getStatusViewers(statusId),
    staleTime: 0,
  })

  useEffect(() => {
    closeRef.current?.focus()
  }, [])

  return (
    <div className="absolute inset-0 z-30 flex items-end justify-center bg-gray-950/55 backdrop-blur-sm animate-fade-in sm:items-center" onClick={onClose}>
      <div
        role="dialog"
        aria-modal="true"
        aria-label="Viewers"
        onClick={(event) => event.stopPropagation()}
        onPointerDown={(event) => event.stopPropagation()}
        className="max-h-[70%] w-full max-w-md overflow-y-auto animate-pop rounded-t-2xl bg-white p-4 text-gray-900 shadow-float sm:rounded-2xl dark:bg-gray-900 dark:text-gray-100"
      >
        <div className="mb-3 flex items-center justify-between">
          <h2 className="flex items-center gap-2 text-lg font-semibold">
            <Eye size={20} />
            {query.data && !query.data.viewersHidden ? `Viewed by ${query.data.viewCount}` : 'Viewers'}
          </h2>
          <button
            ref={closeRef}
            type="button"
            onClick={onClose}
            aria-label="Close viewers"
            className="rounded p-1 hover:bg-gray-100 dark:hover:bg-gray-800"
          >
            <X size={20} />
          </button>
        </div>

        {query.isPending && (
          <p className="flex items-center gap-2 text-sm">
            <Loader2 className="animate-spin" size={16} /> Loading...
          </p>
        )}
        {query.error && (
          <p role="alert" className="text-sm text-red-600">
            {toAppError(query.error).message}
          </p>
        )}
        {query.data?.viewersHidden && (
          <p className="text-sm text-gray-600 dark:text-gray-300">
            Viewers are hidden because your read receipts are off. Turn them on in Settings to see who viewed your
            status.
          </p>
        )}
        {query.data && !query.data.viewersHidden && query.data.viewers.length === 0 && (
          <p className="text-sm text-gray-600 dark:text-gray-300">No views yet.</p>
        )}
        {query.data && !query.data.viewersHidden && query.data.viewers.length > 0 && (
          <ul aria-label="People who viewed this status">
            {query.data.viewers.map((viewer) => (
              <li key={viewer.user.id} className="flex items-center gap-3 py-2">
                <Avatar name={viewer.user.displayName} imageUrl={viewer.user.profileImageUrl} size={36} />
                <span className="min-w-0 flex-1">
                  <span className="block truncate font-medium">{viewer.user.displayName}</span>
                  <span className="block text-xs text-gray-500 dark:text-gray-400">{formatStatusTime(viewer.viewedAt)}</span>
                </span>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}
