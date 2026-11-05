import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Loader2 } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import Alert from '../common/Alert'
import UserRow, { rowButtonClass } from './UserRow'
import { startConversation } from '../../features/messaging/chatActions'
import { toAppError } from '../../services/api/appError'
import { getContacts, removeContact } from '../../services/api/userApi'

// "My contacts": the people I added, with a Remove button.
export default function MyContactsTab({ onView }: { onView: (userId: string) => void }) {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const contactsQuery = useQuery({ queryKey: ['contacts'], queryFn: getContacts })

  const removeMutation = useMutation({
    mutationFn: (contactId: string) => removeContact(contactId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['contacts'] }),
  })

  // Starts (or finds) the chat with this person and opens it.
  const chatMutation = useMutation({
    mutationFn: (userId: string) => startConversation(userId),
    onSuccess: (conversationId) => navigate(`/chats/${conversationId}`),
  })

  if (contactsQuery.isPending) {
    return (
      <p className="flex items-center gap-2">
        <Loader2 className="animate-spin" size={20} /> Loading contacts...
      </p>
    )
  }
  if (contactsQuery.error) {
    return <Alert type="error">{toAppError(contactsQuery.error).message}</Alert>
  }

  const contacts = contactsQuery.data
  return (
    <div>
      {(removeMutation.error || chatMutation.error) && (
        <div className="mb-3">
          <Alert type="error">{toAppError(removeMutation.error ?? chatMutation.error).message}</Alert>
        </div>
      )}
      {contacts.length === 0 ? (
        <p className="text-gray-600 dark:text-gray-300">
          You have no contacts yet. Use "Find people" to add some.
        </p>
      ) : (
        <ul className="stagger grid gap-3 sm:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-4">
          {contacts.map((contact) => (
            <UserRow
              key={contact.id}
              name={contact.nickname || contact.user.displayName}
              username={contact.user.username}
              imageUrl={contact.user.profileImageUrl}
              onView={() => onView(contact.contactUserId)}
            >
              <button
                type="button"
                onClick={() => chatMutation.mutate(contact.contactUserId)}
                disabled={chatMutation.isPending}
                className={rowButtonClass}
              >
                Start a conversation
              </button>
              <button
                type="button"
                onClick={() => removeMutation.mutate(contact.id)}
                disabled={removeMutation.isPending}
                className={rowButtonClass}
              >
                Remove
              </button>
            </UserRow>
          ))}
        </ul>
      )}
    </div>
  )
}
