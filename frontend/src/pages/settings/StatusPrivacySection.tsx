import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Loader2 } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { useLocation } from 'react-router-dom'
import ContactPicker from '../../components/chat/ContactPicker'
import Alert from '../../components/common/Alert'
import { toAppError } from '../../services/api/appError'
import { getStatusPrivacy, updateStatusPrivacy } from '../../services/api/statusApi'
import type { StatusPrivacy, StatusPrivacyMode } from '../../types/status'

type ListMode = Exclude<StatusPrivacyMode, 'CONTACTS'>

const MODES: { value: StatusPrivacyMode; label: string; hint: string }[] = [
  { value: 'CONTACTS', label: 'My contacts', hint: 'Everybody in your contact list.' },
  { value: 'CONTACTS_EXCEPT', label: 'My contacts except...', hint: 'Your contacts, without the people you choose.' },
  { value: 'ONLY_SHARE_WITH', label: 'Only share with...', hint: 'Only the people you choose (from your contacts).' },
]

const PRIVACY_KEY = ['status-privacy'] as const

// Settings > Status privacy: who can see my status updates (API_DOCUMENTATION.md 7.4).
// The two list modes each keep their own list while the user switches between them; only the list of the chosen
// mode is saved ("My contacts" saves an empty list). Reachable from the Status page via /settings#status-privacy.
export default function StatusPrivacySection() {
  const queryClient = useQueryClient()
  const location = useLocation()
  const sectionRef = useRef<HTMLElement>(null)
  const privacyQuery = useQuery({ queryKey: PRIVACY_KEY, queryFn: getStatusPrivacy })

  // What the user is editing (null until the saved settings have loaded).
  const [draft, setDraft] = useState<{ mode: StatusPrivacyMode; lists: Record<ListMode, string[]> } | null>(null)
  const [saved, setSaved] = useState(false)

  // Start editing from the saved settings, once they are here.
  const loaded = privacyQuery.data
  if (loaded && draft === null) {
    const lists: Record<ListMode, string[]> = { CONTACTS_EXCEPT: [], ONLY_SHARE_WITH: [] }
    if (loaded.mode !== 'CONTACTS') lists[loaded.mode] = loaded.userIds
    setDraft({ mode: loaded.mode, lists })
  }

  // Coming from the Status page's "Status privacy" link: scroll this section into view.
  useEffect(() => {
    if (location.hash === '#status-privacy' && loaded) sectionRef.current?.scrollIntoView?.({ block: 'start' })
  }, [location.hash, loaded])

  const saveMutation = useMutation({
    mutationFn: (body: StatusPrivacy) => updateStatusPrivacy(body),
    onSuccess: (updated) => {
      queryClient.setQueryData(PRIVACY_KEY, updated)
      setSaved(true)
    },
  })

  function chooseMode(mode: StatusPrivacyMode) {
    setSaved(false)
    setDraft((current) => (current ? { ...current, mode } : current))
  }

  function changeList(ids: string[]) {
    setSaved(false)
    setDraft((current) => (current && current.mode !== 'CONTACTS' ? { ...current, lists: { ...current.lists, [current.mode]: ids } } : current))
  }

  function save() {
    if (!draft) return
    setSaved(false)
    saveMutation.mutate({ mode: draft.mode, userIds: draft.mode === 'CONTACTS' ? [] : draft.lists[draft.mode] })
  }

  return (
    <section
      id="status-privacy"
      ref={sectionRef}
      aria-labelledby="status-privacy-title"
      className="scroll-mt-4 card p-6"
    >
      <h2 id="status-privacy-title" className="mb-1 text-lg font-semibold">
        Status privacy
      </h2>
      <p className="mb-4 text-sm text-gray-600 dark:text-gray-300">
        Who can see my status updates. Changes apply right away, also to status updates you already posted. People who
        blocked you, or whom you blocked, never see them.
      </p>

      {privacyQuery.isPending && (
        <p className="flex items-center gap-2">
          <Loader2 className="animate-spin" size={20} /> Loading...
        </p>
      )}
      {privacyQuery.error && <Alert type="error">{toAppError(privacyQuery.error).message}</Alert>}

      {draft && (
        <>
          <fieldset className="mb-4">
            <legend className="sr-only">Who can see my status</legend>
            {MODES.map((option) => (
              <div key={option.value} className="flex items-start gap-2 py-1.5">
                <input
                  id={`status-privacy-${option.value}`}
                  type="radio"
                  name="status-privacy-mode"
                  value={option.value}
                  checked={draft.mode === option.value}
                  onChange={() => chooseMode(option.value)}
                  aria-describedby={`status-privacy-${option.value}-hint`}
                  className="mt-1 accent-brand-600"
                />
                <div>
                  <label htmlFor={`status-privacy-${option.value}`} className="block cursor-pointer text-sm font-medium">
                    {option.label}
                  </label>
                  <p id={`status-privacy-${option.value}-hint`} className="text-xs text-gray-500 dark:text-gray-400">
                    {option.hint}
                  </p>
                </div>
              </div>
            ))}
          </fieldset>

          {draft.mode !== 'CONTACTS' && (
            <div className="mb-4 flex max-h-80 flex-col">
              <p className="mb-2 text-sm font-medium">
                {draft.mode === 'CONTACTS_EXCEPT' ? 'Hide my status from:' : 'Share my status only with:'}
              </p>
              <ContactPicker selected={draft.lists[draft.mode]} onChange={changeList} />
              {draft.mode === 'ONLY_SHARE_WITH' && draft.lists.ONLY_SHARE_WITH.length === 0 && (
                <p className="mt-2 text-xs text-amber-700 dark:text-amber-400">Nobody is chosen: nobody will see your status.</p>
              )}
            </div>
          )}

          {saved && (
            <div className="mb-4">
              <Alert type="success">Status privacy saved.</Alert>
            </div>
          )}
          {saveMutation.error && (
            <div className="mb-4">
              <Alert type="error">{toAppError(saveMutation.error).message}</Alert>
            </div>
          )}

          <button
            type="button"
            onClick={save}
            disabled={saveMutation.isPending}
            className="flex items-center gap-2 rounded bg-brand-600 px-4 py-2 text-sm font-medium text-white hover:bg-brand-700 disabled:opacity-50"
          >
            {saveMutation.isPending && <Loader2 className="animate-spin" size={16} />}
            Save status privacy
          </button>
        </>
      )}
    </section>
  )
}
