import { useQuery } from '@tanstack/react-query'
import { Loader2, X } from 'lucide-react'
import { useState } from 'react'
import Alert from '../common/Alert'
import Avatar from '../common/Avatar'
import { toAppError } from '../../services/api/appError'
import { getContacts } from '../../services/api/userApi'

// Choose people from my contacts (for a new group, or to add members to a group).
// - a filter box, a checkbox per contact, and the chosen people as chips at the top
// - `refusedIds`: people the server said cannot be added (privacy settings); they are marked so the user can untick them
// - `excludeIds`: people who must not be offered (for example the current members)
export default function ContactPicker({
  selected,
  onChange,
  excludeIds = [],
  refusedIds = [],
}: {
  selected: string[]
  onChange: (ids: string[]) => void
  excludeIds?: string[]
  refusedIds?: string[]
}) {
  const [filter, setFilter] = useState('')
  const contactsQuery = useQuery({ queryKey: ['contacts'], queryFn: getContacts })

  if (contactsQuery.isPending) {
    return (
      <p className="flex items-center gap-2 text-sm">
        <Loader2 className="animate-spin" size={16} /> Loading contacts...
      </p>
    )
  }
  if (contactsQuery.error) {
    return <Alert type="error">{toAppError(contactsQuery.error).message}</Alert>
  }

  const contacts = contactsQuery.data
    .filter((contact) => !excludeIds.includes(contact.contactUserId))
    .map((contact) => ({ id: contact.contactUserId, name: contact.nickname || contact.user.displayName, user: contact.user }))
  const nameOf = (id: string) => contacts.find((contact) => contact.id === id)?.name ?? 'Unknown user'
  const needle = filter.trim().toLowerCase()
  const visible = needle
    ? contacts.filter((contact) => contact.name.toLowerCase().includes(needle) || contact.user.username.includes(needle))
    : contacts

  function toggle(id: string) {
    onChange(selected.includes(id) ? selected.filter((other) => other !== id) : [...selected, id])
  }

  return (
    <div className="flex min-h-0 flex-col gap-2">
      {selected.length > 0 && (
        <ul aria-label="Selected people" className="flex flex-wrap gap-1">
          {selected.map((id) => (
            <li
              key={id}
              className={`flex items-center gap-1 rounded-full px-2 py-0.5 text-sm ${
                refusedIds.includes(id)
                  ? 'bg-red-100 text-red-800 dark:bg-red-950 dark:text-red-300'
                  : 'bg-brand-100 text-brand-800 dark:bg-brand-900 dark:text-brand-100'
              }`}
            >
              {nameOf(id)}
              <button type="button" aria-label={`Remove ${nameOf(id)}`} onClick={() => toggle(id)} className="rounded-full hover:bg-black/10">
                <X size={14} />
              </button>
            </li>
          ))}
        </ul>
      )}

      <input
        type="search"
        value={filter}
        onChange={(event) => setFilter(event.target.value)}
        placeholder="Search contacts"
        aria-label="Search contacts"
        className="rounded-xl border border-gray-200 bg-white px-3.5 py-2.5 text-sm shadow-sm outline-none transition focus:border-brand-500 focus:ring-4 focus:ring-brand-500/15 dark:border-gray-700 dark:bg-gray-800"
      />

      {contacts.length === 0 ? (
        <p className="text-sm text-gray-600 dark:text-gray-300">
          No contacts to choose from. Add people on the Contacts page first.
        </p>
      ) : (
        <ul aria-label="Contacts" className="min-h-0 flex-1 overflow-y-auto">
          {visible.map((contact) => {
            const refused = refusedIds.includes(contact.id)
            return (
              <li key={contact.id}>
                <label className="flex cursor-pointer items-center gap-3 rounded px-2 py-2 hover:bg-gray-50 dark:hover:bg-gray-800/70">
                  <input
                    type="checkbox"
                    checked={selected.includes(contact.id)}
                    onChange={() => toggle(contact.id)}
                    className="h-4 w-4 accent-brand-600"
                  />
                  <Avatar name={contact.name} imageUrl={contact.user.profileImageUrl} size={32} />
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-sm font-medium">{contact.name}</span>
                    {refused ? (
                      <span className="block text-xs text-red-600 dark:text-red-400">
                        can't be added (privacy settings)
                      </span>
                    ) : (
                      <span className="block truncate text-xs text-gray-500 dark:text-gray-400">@{contact.user.username}</span>
                    )}
                  </span>
                </label>
              </li>
            )
          })}
          {visible.length === 0 && <li className="px-2 py-2 text-sm text-gray-500">Nobody matches "{filter}".</li>}
        </ul>
      )}
    </div>
  )
}
