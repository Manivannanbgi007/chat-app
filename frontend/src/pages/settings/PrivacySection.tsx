import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Loader2 } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useForm } from 'react-hook-form'
import Alert from '../../components/common/Alert'
import FormField, { inputClass } from '../../components/common/FormField'
import SubmitButton from '../../components/common/SubmitButton'
import { toAppError } from '../../services/api/appError'
import { getPrivacy, updatePrivacy } from '../../services/api/userApi'
import type { Privacy, Visibility } from '../../types/user'

const OPTIONS: { value: Visibility; label: string }[] = [
  { value: 'EVERYONE', label: 'Everyone' },
  { value: 'CONTACTS', label: 'My contacts' },
  { value: 'NOBODY', label: 'Nobody' },
]

// The three "who can see..." selects, who can add me to groups, and the read receipts switch.
const SELECTS: { name: 'lastSeen' | 'profilePhoto' | 'about' | 'groupAdd'; label: string }[] = [
  { name: 'lastSeen', label: 'Who can see my last seen' },
  { name: 'profilePhoto', label: 'Who can see my profile photo' },
  { name: 'about', label: 'Who can see my about' },
  { name: 'groupAdd', label: 'Who can add me to groups' },
]

export default function PrivacySection() {
  const queryClient = useQueryClient()
  const [saved, setSaved] = useState(false)
  const privacyQuery = useQuery({ queryKey: ['privacy'], queryFn: getPrivacy })

  const { register, handleSubmit, reset } = useForm<Privacy>()

  // Fill the form when the settings have loaded.
  useEffect(() => {
    if (privacyQuery.data) {
      reset(privacyQuery.data)
    }
  }, [privacyQuery.data, reset])

  const saveMutation = useMutation({
    mutationFn: (values: Privacy) => updatePrivacy(values),
    onSuccess: (updated) => {
      queryClient.setQueryData(['privacy'], updated)
      setSaved(true)
    },
  })

  function onSubmit(values: Privacy) {
    setSaved(false)
    saveMutation.mutate(values)
  }

  return (
    <section className="card p-6">
      <h2 className="mb-4 text-lg font-semibold">Privacy</h2>

      {privacyQuery.isPending && (
        <p className="flex items-center gap-2">
          <Loader2 className="animate-spin" size={20} /> Loading...
        </p>
      )}
      {privacyQuery.error && <Alert type="error">{toAppError(privacyQuery.error).message}</Alert>}

      {privacyQuery.data && (
        <form onSubmit={handleSubmit(onSubmit)}>
          {saved && (
            <div className="mb-4">
              <Alert type="success">Privacy settings saved.</Alert>
            </div>
          )}
          {saveMutation.error && (
            <div className="mb-4">
              <Alert type="error">{toAppError(saveMutation.error).message}</Alert>
            </div>
          )}

          {SELECTS.map((select) => (
            <FormField key={select.name} label={select.label} htmlFor={select.name}>
              <select id={select.name} className={inputClass} {...register(select.name)}>
                {OPTIONS.map((option) => (
                  <option key={option.value} value={option.value}>
                    {option.label}
                  </option>
                ))}
              </select>
            </FormField>
          ))}

          <label className="mb-4 flex items-center gap-2 text-sm">
            <input type="checkbox" {...register('readReceipts')} />
            Send read receipts (blue ticks)
          </label>

          <SubmitButton loading={saveMutation.isPending}>Save privacy settings</SubmitButton>
        </form>
      )}
    </section>
  )
}
