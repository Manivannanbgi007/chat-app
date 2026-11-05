import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link } from 'react-router-dom'
import Alert from '../../components/common/Alert'
import FormField, { inputClass } from '../../components/common/FormField'
import SubmitButton from '../../components/common/SubmitButton'
import { toAppError } from '../../services/api/appError'
import { forgotPassword } from '../../services/api/authApi'
import { forgotPasswordSchema, type ForgotPasswordForm } from '../../validations/authSchemas'

export default function ForgotPasswordPage() {
  const [sent, setSent] = useState(false)
  const [serverError, setServerError] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    formState: { errors, isSubmitting },
  } = useForm<ForgotPasswordForm>({
    resolver: zodResolver(forgotPasswordSchema),
    defaultValues: { identifier: '' },
  })

  async function onSubmit(values: ForgotPasswordForm) {
    setServerError(null)
    try {
      await forgotPassword(values.identifier.trim())
      setSent(true)
    } catch (error) {
      setServerError(toAppError(error).message)
    }
  }

  return (
    <section className="card animate-fade-up p-7 shadow-float sm:p-8">
      <h1 className="mb-5 text-2xl font-bold tracking-tight">Forgot your password?</h1>

      {sent ? (
        // The server gives the same answer whether the account exists or not, so we do too.
        <Alert type="success">
          If an account exists for that email or phone number, we have sent a link to reset the password. Check your inbox.
        </Alert>
      ) : (
        <>
          <p className="mb-4 text-sm text-gray-600 dark:text-gray-300">
            Enter your email or phone number and we will send you a reset link.
          </p>
          {serverError && (
            <div className="mb-4">
              <Alert type="error">{serverError}</Alert>
            </div>
          )}
          <form onSubmit={handleSubmit(onSubmit)} noValidate>
            <FormField label="Email or phone number" htmlFor="identifier" hint="You can also type your username." error={errors.identifier?.message}>
              <input id="identifier" autoComplete="username" className={inputClass} {...register('identifier')} />
            </FormField>
            <SubmitButton loading={isSubmitting}>Send reset link</SubmitButton>
          </form>
        </>
      )}

      <p className="mt-4 text-center text-sm">
        <Link to="/login" className="text-brand-600 hover:underline">
          Back to log in
        </Link>
      </p>
    </section>
  )
}
