import { ArrowLeft, X } from 'lucide-react'
import { useId, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import Alert from '../common/Alert'
import ContactPicker from './ContactPicker'
import { createGroup, describeGroupError, refusedUserIds } from '../../features/messaging/chatActions'

const MAX_NAME = 100
const MAX_DESCRIPTION = 500

// "New group" in two steps: 1. choose people from my contacts, 2. name and description.
// On success the new group opens. If the server refuses some people (their privacy settings),
// we go back to step 1 with those people marked, so they can be unticked.
export default function NewGroupDialog({ onClose }: { onClose: () => void }) {
  const navigate = useNavigate()
  const titleId = useId()
  const [step, setStep] = useState<'people' | 'details'>('people')
  const [selected, setSelected] = useState<string[]>([])
  const [refused, setRefused] = useState<string[]>([])
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [nameError, setNameError] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const refusedStillSelected = selected.some((id) => refused.includes(id))

  async function handleCreate(event: FormEvent) {
    event.preventDefault()
    const trimmed = name.trim()
    if (trimmed.length < 1 || trimmed.length > MAX_NAME) {
      setNameError(`Enter a group name (1-${MAX_NAME} characters).`)
      return
    }
    setNameError(null)
    setError(null)
    setBusy(true)
    try {
      const id = await createGroup(trimmed, description, selected)
      onClose()
      navigate(`/chats/${id}`)
    } catch (caught) {
      const refusedIds = refusedUserIds(caught)
      setError(describeGroupError(caught))
      if (refusedIds.length > 0) {
        setRefused(refusedIds)
        setStep('people')
      }
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
        <div className="mb-3 flex items-center gap-2">
          {step === 'details' && (
            <button type="button" onClick={() => setStep('people')} aria-label="Back to choosing people" className="rounded p-1 hover:bg-gray-100 dark:hover:bg-gray-800">
              <ArrowLeft size={20} />
            </button>
          )}
          <h2 id={titleId} className="flex-1 text-lg font-semibold">
            {step === 'people' ? 'New group: add members' : 'New group: name'}
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

        {step === 'people' ? (
          <>
            <div className="min-h-0 flex-1 overflow-hidden sm:max-h-[55vh]">
              <ContactPicker selected={selected} onChange={setSelected} refusedIds={refused} />
            </div>
            <div className="mt-3 flex items-center justify-between gap-2">
              <span className="text-sm text-gray-500 dark:text-gray-400">{selected.length} selected</span>
              <button
                type="button"
                onClick={() => {
                  setError(null)
                  setStep('details')
                }}
                disabled={selected.length === 0 || refusedStillSelected}
                className="rounded bg-brand-600 px-4 py-2 text-sm font-medium text-white hover:bg-brand-700 disabled:cursor-not-allowed disabled:opacity-50"
              >
                Next
              </button>
            </div>
          </>
        ) : (
          <form onSubmit={handleCreate} noValidate className="flex flex-col gap-3">
            <label className="text-sm font-medium">
              Group name
              <input
                value={name}
                onChange={(event) => setName(event.target.value)}
                maxLength={MAX_NAME}
                aria-invalid={nameError ? true : undefined}
                aria-describedby={nameError ? `${titleId}-name-error` : undefined}
                className="mt-1 block w-full rounded-xl border border-gray-200 bg-white px-3.5 py-2.5 font-normal shadow-sm outline-none transition focus:border-brand-500 focus:ring-4 focus:ring-brand-500/15 dark:border-gray-700 dark:bg-gray-800"
              />
            </label>
            {nameError && (
              <p id={`${titleId}-name-error`} className="-mt-2 text-sm text-red-600 dark:text-red-400">
                {nameError}
              </p>
            )}
            <label className="text-sm font-medium">
              Description (optional)
              <textarea
                value={description}
                onChange={(event) => setDescription(event.target.value)}
                maxLength={MAX_DESCRIPTION}
                rows={3}
                className="mt-1 block w-full resize-none rounded-xl border border-gray-200 bg-white px-3.5 py-2.5 font-normal shadow-sm outline-none transition focus:border-brand-500 focus:ring-4 focus:ring-brand-500/15 dark:border-gray-700 dark:bg-gray-800"
              />
            </label>
            <p className="text-right text-xs text-gray-500">
              {description.length} / {MAX_DESCRIPTION}
            </p>
            <button
              type="submit"
              disabled={busy}
              className="rounded bg-brand-600 px-4 py-2 text-sm font-medium text-white hover:bg-brand-700 disabled:opacity-50"
            >
              {busy ? 'Creating...' : 'Create group'}
            </button>
          </form>
        )}
      </div>
    </div>
  )
}
