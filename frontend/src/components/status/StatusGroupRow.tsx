import { useId } from 'react'
import { formatStatusTime } from '../../features/status/statusRules'
import type { StatusGroup } from '../../types/status'
import StatusRing from './StatusRing'

// One person in "Recent updates" / "Viewed updates": ring (one segment per status), name and the time of the
// newest status. The whole row is a button that opens the story viewer.
export default function StatusGroupRow({ group, onOpen }: { group: StatusGroup; onOpen: () => void }) {
  const timeId = useId()
  const name = group.owner.displayName
  return (
    <li className="card animate-fade-up transition duration-200 hover:-translate-y-0.5 hover:shadow-float">
      <button
        type="button"
        data-testid="status-group"
        aria-label={name}
        aria-describedby={timeId}
        onClick={onOpen}
        className="flex w-full items-center gap-3 rounded-2xl p-3.5 text-left"
      >
        <StatusRing
          name={name}
          imageUrl={group.owner.profileImageUrl}
          segments={group.statuses.map((status) => !status.viewedByMe)}
        />
        <span className="min-w-0 flex-1">
          <span className="block truncate font-medium">{name}</span>
          <span id={timeId} className="block text-sm text-gray-500 dark:text-gray-400">
            {formatStatusTime(group.latestAt)}
          </span>
        </span>
      </button>
    </li>
  )
}
