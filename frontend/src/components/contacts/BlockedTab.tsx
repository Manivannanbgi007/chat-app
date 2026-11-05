import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Loader2 } from 'lucide-react'
import { toAppError } from '../../services/api/appError'
import { getBlockedUsers, unblockUser } from '../../services/api/userApi'
import Alert from '../common/Alert'
import UserRow, { rowButtonClass } from './UserRow'

// "Blocked": people I blocked, with an Unblock button. Also used in Settings.
export default function BlockedTab({ onView }: { onView: (userId: string) => void }) {
  const queryClient = useQueryClient()
  const blockedQuery = useQuery({ queryKey: ['blocked'], queryFn: getBlockedUsers })

  const unblockMutation = useMutation({
    mutationFn: (userId: string) => unblockUser(userId),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['blocked'] })
      void queryClient.invalidateQueries({ queryKey: ['user-search'] })
    },
  })

  if (blockedQuery.isPending) {
    return (
      <p className="flex items-center gap-2">
        <Loader2 className="animate-spin" size={20} /> Loading...
      </p>
    )
  }
  if (blockedQuery.error) {
    return <Alert type="error">{toAppError(blockedQuery.error).message}</Alert>
  }

  return (
    <div>
      {unblockMutation.error && (
        <div className="mb-3">
          <Alert type="error">{toAppError(unblockMutation.error).message}</Alert>
        </div>
      )}
      {blockedQuery.data.length === 0 ? (
        <p className="text-gray-600 dark:text-gray-300">You have not blocked anyone.</p>
      ) : (
        <ul className="stagger grid gap-3 sm:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-4">
          {blockedQuery.data.map((person) => (
            <UserRow
              key={person.id}
              name={person.displayName}
              username={person.username}
              imageUrl={person.profileImageUrl}
              onView={() => onView(person.id)}
            >
              <button
                type="button"
                onClick={() => unblockMutation.mutate(person.id)}
                disabled={unblockMutation.isPending}
                className={rowButtonClass}
              >
                Unblock
              </button>
            </UserRow>
          ))}
        </ul>
      )}
    </div>
  )
}
