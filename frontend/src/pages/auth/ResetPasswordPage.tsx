import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import Alert from '../../components/common/Alert'
import FormField from '../../components/common/FormField'
import PasswordInput from '../../components/common/PasswordInput'
import SubmitButton from '../../components/common/SubmitButton'
import { friendlyAuthError } from '../../features/authentication/authErrors'
import { toAppError } from '../../services/api/appError'
import { resetPassword } from '../../services/api/authApi'
import { resetPasswordSchema, type ResetPasswordForm } from '../../validations/authSchemas'

// Opened from the link in the reset email: /reset-password?token=...
export default function ResetPasswordPage() {
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const token = params.get('token')
  const [serverError, setServerError] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<ResetPasswordForm>({
    resolver: zodResolver(resetPasswordSchema),
    defaultValues: { newPassword: '', confirmPassword: '' },
  })

  async function onSubmit(values: ResetPasswordForm) {
    if (!token) return
    setServerError(null)
    try {
      await resetPassword({ token, newPassword: values.newPassword })
      navigate('/login', { state: { notice: 'Your password was changed. Log in with the new password.' } })
    } catch (error) {
      setServerError(friendlyAuthError(toAppError(error)))
    }
  }

  return (
    <section className="card animate-fade-up p-7 shadow-float sm:p-8">
      <h1 className="mb-5 text-2xl font-bold tracking-tight">Choose a new password</h1>

      {!token ? (
        <Alert type="error">This reset link is not valid. Please request a new one.</Alert>
      ) : (
        <>
          {serverError && (
            <div className="mb-4">
              <Alert type="error">{serverError}</Alert>
            </div>
          )}
          <form onSubmit={handleSubmit(onSubmit)} noValidate>
            <FormField
              label="New password"
              htmlFor="newPassword"
              error={errors.newPassword?.message}
              hint="At least 8 characters with a letter and a number"
            >
              <PasswordInput id="newPassword" autoComplete="new-password" {...register('newPassword')} />
            </FormField>
            <FormField label="Confirm new password" htmlFor="confirmPassword" error={errors.confirmPassword?.message}>
              <PasswordInput id="confirmPassword" autoComplete="new-password" {...register('confirmPassword')} />
            </FormField>
            <SubmitButton loading={isSubmitting}>Change password</SubmitButton>
          </form>
        </>
      )}

      <p className="mt-4 text-center text-sm">
        <Link to="/forgot-password" className="text-brand-600 hover:underline">
          Request a new link
        </Link>
      </p>
    </section>
  )
}
