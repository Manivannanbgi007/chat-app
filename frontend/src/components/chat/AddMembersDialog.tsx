import { X } from 'lucide-react'
import { useId, useState } from 'react'
import Alert from '../common/Alert'
import ContactPicker from './ContactPicker'
import { addGroupMembers, describeGroupError, refusedUserIds } from '../../features/messaging/chatActions'
import type { Conversation } from '../../types/chat'

// Admins add people from their contacts. The current members are not offered.
// People the server refuses (privacy settings) are marked; untick them and try again.
export default function AddMembersDialog({ conversation, onClose }: { conversation: Conversation; onClose: () => void }) {
  const titleId = useId()
  const [selected, setSelected] = useState<string[]>([])
  const [refused, setRefused] = useState<string[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const refusedStillSelected = selected.some((id) => refused.includes(id))

  async function handleAdd() {
    setBusy(true)
    setError(null)
    try {
      await addGroupMembers(conversation.id, selected)
      onClose()
    } catch (caught) {
      setRefused(refusedUserIds(caught))
      setError(describeGroupError(caught))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="fixed inset-0 z-30 flex items-center justify-center bg-gray-950/55 backdrop-blur-sm animate-fade-in sm:p-4">
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className="flex h-full w-full flex-col bg-white p-4 shadow-lg sm:h-auto sm:max-h-[90vh] sm:max-w-md sm:animate-pop sm:rounded-2xl dark:bg-gray-900"
      >
        <div className="mb-3 flex items-center">
          <h2 id={titleId} className="flex-1 text-lg font-semibold">
            Add members
          </h2>
          <button type="button" onClick={onClose} aria-label="Close" className="rounded p-1 hover:bg-gray-100 dark:hover:bg-gray-800">
            <X size={20} />
          </button>
        </div>
        {error && (
          <div className="mb-3">
            <Alert type="error">{error}</Alert>
          </div>
        )}
        <div className="min-h-0 flex-1 overflow-hidden sm:max-h-[55vh]">
          <ContactPicker
            selected={selected}
            onChange={setSelected}
            excludeIds={conversation.participants.map((person) => person.id)}
            refusedIds={refused}
          />
        </div>
        <div className="mt-3 flex justify-end">
          <button
            type="button"
            onClick={() => void handleAdd()}
            disabled={busy || selected.length === 0 || refusedStillSelected}
            className="rounded bg-brand-600 px-4 py-2 text-sm font-medium text-white hover:bg-brand-700 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {busy ? 'Adding...' : 'Add to group'}
          </button>
        </div>
      </div>
    </div>
  )
}
