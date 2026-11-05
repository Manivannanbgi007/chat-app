import type { ReactNode } from 'react'
import Avatar from '../common/Avatar'

// One person in a list: avatar, name, @username and action buttons (passed as children).
export default function UserRow({
  name,
  username,
  imageUrl,
  onView,
  children,
}: {
  name: string
  username: string
  imageUrl: string | null
  onView: () => void
  children: ReactNode
}) {
  return (
    <li className="card flex animate-fade-up flex-col gap-3 p-4 transition duration-200 hover:-translate-y-0.5 hover:shadow-float">
      <button type="button" onClick={onView} aria-label={`View profile of ${name}`} className="flex min-w-0 flex-1 items-center gap-3 text-left">
        <Avatar name={name} imageUrl={imageUrl} size={48} />
        <span className="min-w-0">
          <span className="block truncate font-medium">{name}</span>
          <span className="block truncate text-sm text-gray-500 dark:text-gray-400">@{username}</span>
        </span>
      </button>
      <div className="flex flex-wrap justify-end gap-2">{children}</div>
    </li>
  )
}

// Style for the small buttons in a row.
export const rowButtonClass =
  'rounded-xl border border-gray-200 px-3 py-1.5 text-sm font-medium hover:border-brand-300 hover:bg-brand-50 hover:text-brand-700 disabled:cursor-not-allowed disabled:opacity-50 dark:border-gray-700 dark:hover:bg-brand-950/50 dark:hover:text-brand-300'
