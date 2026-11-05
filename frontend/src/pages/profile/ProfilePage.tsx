import { zodResolver } from '@hookform/resolvers/zod'
import PageHeader from '../../components/common/PageHeader'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Loader2 } from 'lucide-react'
import { useEffect, useState } from 'react'
import { useForm } from 'react-hook-form'
import Alert from '../../components/common/Alert'
import Avatar from '../../components/common/Avatar'
import FormField, { inputClass } from '../../components/common/FormField'
import SubmitButton from '../../components/common/SubmitButton'
import { toAppError } from '../../services/api/appError'
import { getMyProfile, updateMyProfile } from '../../services/api/userApi'
import { useAuthStore } from '../../store/authStore'
import { profileSchema, type ProfileForm } from '../../validations/userSchemas'

export default function ProfilePage() {
  const queryClient = useQueryClient()
  const [saved, setSaved] = useState(false)

  const profileQuery = useQuery({ queryKey: ['me'], queryFn: getMyProfile })
  const profile = profileQuery.data

  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<ProfileForm>({
    resolver: zodResolver(profileSchema),
    defaultValues: { displayName: '', about: '' },
  })

  // Fill the form when the profile has loaded.
  useEffect(() => {
    if (profile) {
      reset({ displayName: profile.displayName, about: profile.about ?? '' })
    }
  }, [profile, reset])

  const saveMutation = useMutation({
    mutationFn: (values: { displayName: string; about: string }) => updateMyProfile(values),
    onSuccess: (updated) => {
      queryClient.setQueryData(['me'], updated)
      useAuthStore.getState().updateUser({ displayName: updated.displayName }) // header shows the new name
      setSaved(true)
    },
  })

  function onSubmit(values: ProfileForm) {
    setSaved(false)
    saveMutation.mutate({ displayName: values.displayName.trim(), about: values.about })
  }

  if (profileQuery.error && !profile) {
    const error = toAppError(profileQuery.error)
    return (
      <div className="space-y-3">
        <Alert type="error">
          {error.kind === 'NOT_FOUND'
            ? 'Your profile is still being set up. Please try again in a moment.'
            : error.message}
        </Alert>
        <button
          type="button"
          onClick={() => profileQuery.refetch()}
          className="rounded bg-brand-600 px-3 py-1 text-white hover:bg-brand-700"
        >
          Try again
        </button>
      </div>
    )
  }

  if (!profile) {
    return (
      <p className="flex items-center gap-2">
        <Loader2 className="animate-spin" size={20} /> Loading your profile...
      </p>
    )
  }

  return (
    <section>
      <PageHeader title="Profile" subtitle="How other people see you, and your account details." />

      <div className="grid items-start gap-6 lg:grid-cols-[22rem_1fr] xl:grid-cols-[26rem_1fr]">
        {/* identity card */}
        <div className="card overflow-hidden">
          <div className="h-24 bg-gradient-to-br from-brand-500 via-brand-600 to-brand-800" />
          <div className="-mt-12 flex flex-col items-center px-6 pb-6 text-center">
            <div className="rounded-full bg-white p-1 shadow-float dark:bg-gray-900">
              <Avatar name={profile.displayName} imageUrl={profile.profileImageUrl} size={88} />
            </div>
            <h2 className="mt-3 text-2xl font-bold tracking-tight">{profile.displayName}</h2>
            <p className="text-sm text-gray-500 dark:text-gray-400">@{profile.username}</p>
            {profile.about && <p className="mt-3 text-sm text-gray-600 dark:text-gray-300">{profile.about}</p>}
            <dl className="mt-6 grid w-full grid-cols-[auto_1fr] gap-x-4 gap-y-2 rounded-2xl bg-gray-50 p-4 text-left text-sm dark:bg-gray-800/60">
              <dt className="text-gray-500 dark:text-gray-400">Username</dt>
              <dd>@{profile.username}</dd>
              <dt className="text-gray-500 dark:text-gray-400">Email</dt>
              <dd className="break-all">{profile.email}</dd>
              {profile.phone && (
                <>
                  <dt className="text-gray-500 dark:text-gray-400">Phone</dt>
                  <dd>{profile.phone}</dd>
                </>
              )}
            </dl>
          </div>
        </div>

        {/* edit form */}
        <div className="card max-w-2xl p-6 sm:p-8">
          <h2 className="mb-4 text-lg font-semibold">Edit your profile</h2>
          {saved && (
            <div className="mb-4">
              <Alert type="success">Profile saved.</Alert>
            </div>
          )}
          {saveMutation.error && (
            <div className="mb-4">
              <Alert type="error">{toAppError(saveMutation.error).message}</Alert>
            </div>
          )}
          <form onSubmit={handleSubmit(onSubmit)} noValidate>
            <FormField label="Display name" htmlFor="displayName" error={errors.displayName?.message}>
              <input id="displayName" className={inputClass} {...register('displayName')} />
            </FormField>
            <FormField label="About" htmlFor="about" error={errors.about?.message} hint="Up to 139 characters">
              <textarea id="about" rows={4} className={inputClass} {...register('about')} />
            </FormField>
            <p className="mb-4 text-xs text-gray-500 dark:text-gray-400">Profile photo upload is coming in a later phase.</p>
            <SubmitButton loading={saveMutation.isPending}>Save changes</SubmitButton>
          </form>
        </div>
      </div>
    </section>
  )
}
