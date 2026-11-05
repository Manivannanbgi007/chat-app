import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Loader2, Search } from 'lucide-react'
import { useState } from 'react'
import { friendlyAuthError } from '../../features/authentication/authErrors'
import { useDebouncedValue } from '../../hooks/useDebouncedValue'
import { toAppError } from '../../services/api/appError'
import { addContact, blockUser, getContacts, searchUsers } from '../../services/api/userApi'
import Alert from '../common/Alert'
import { inputClass } from '../common/FormField'
import UserRow, { rowButtonClass } from './UserRow'

const MIN_QUERY_LENGTH = 2

// "Find people": search by username, name or phone, then add as contact or block.
export default function FindPeopleTab({ onView }: { onView: (userId: string) => void }) {
  const queryClient = useQueryClient()
  const [text, setText] = useState('')
  // Wait until the user stops typing for 400 ms before asking the server.
  const query = useDebouncedValue(text.trim(), 400)
  const searchEnabled = query.length >= MIN_QUERY_LENGTH

  const searchQuery = useQuery({
    queryKey: ['user-search', query],
    queryFn: () => searchUsers(query),
    enabled: searchEnabled,
  })
  // Needed to show "In contacts" instead of the Add button.
  const contactsQuery = useQuery({ queryKey: ['contacts'], queryFn: getContacts })
  const contactIds = new Set(contactsQuery.data?.map((contact) => contact.contactUserId))

  const addMutation = useMutation({
    mutationFn: (userId: string) => addContact({ contactUserId: userId }),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['contacts'] }),
  })
  const blockMutation = useMutation({
    mutationFn: (userId: string) => blockUser(userId),
    onSuccess: () => {
      // The blocked person disappears from search results and appears in the blocked list.
      void queryClient.invalidateQueries({ queryKey: ['user-search'] })
      void queryClient.invalidateQueries({ queryKey: ['blocked'] })
    },
  })

  const actionError = addMutation.error ?? blockMutation.error

  return (
    <div>
      <div className="relative mb-4">
        <Search size={18} className="pointer-events-none absolute left-3 top-1/2 -translate-y-1/2 text-gray-400" />
        <input
          type="search"
          aria-label="Search people"
          placeholder="Search by name, username or phone"
          value={text}
          onChange={(event) => setText(event.target.value)}
          className={`${inputClass} pl-9`}
        />
      </div>

      {actionError && (
        <div className="mb-3">
          <Alert type="error">{friendlyAuthError(toAppError(actionError))}</Alert>
        </div>
      )}

      {!searchEnabled && (
        <p className="text-sm text-gray-600 dark:text-gray-300">Type at least {MIN_QUERY_LENGTH} characters to search.</p>
      )}
      {searchEnabled && searchQuery.isFetching && (
        <p className="flex items-center gap-2">
          <Loader2 className="animate-spin" size={20} /> Searching...
        </p>
      )}
      {searchEnabled && searchQuery.error && <Alert type="error">{toAppError(searchQuery.error).message}</Alert>}
      {searchEnabled && !searchQuery.isFetching && searchQuery.data?.length === 0 && (
        <p className="text-gray-600 dark:text-gray-300">No one found.</p>
      )}

      {searchEnabled && searchQuery.data && searchQuery.data.length > 0 && (
        <ul className="stagger grid gap-3 sm:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-4">
          {searchQuery.data.map((person) => (
            <UserRow
              key={person.id}
              name={person.displayName}
              username={person.username}
              imageUrl={person.profileImageUrl}
              onView={() => onView(person.id)}
            >
              {contactIds.has(person.id) ? (
                <span className="px-2 py-1 text-sm text-brand-600">In contacts</span>
              ) : (
                <button
                  type="button"
                  onClick={() => addMutation.mutate(person.id)}
                  disabled={addMutation.isPending}
                  className={rowButtonClass}
                >
                  Add contact
                </button>
              )}
              <button
                type="button"
                onClick={() => blockMutation.mutate(person.id)}
                disabled={blockMutation.isPending}
                className={rowButtonClass}
              >
                Block
              </button>
            </UserRow>
          ))}
        </ul>
      )}
    </div>
  )
}
