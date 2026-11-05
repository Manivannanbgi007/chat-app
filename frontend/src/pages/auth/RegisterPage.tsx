import { zodResolver } from '@hookform/resolvers/zod'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { Link, useNavigate } from 'react-router-dom'
import Alert from '../../components/common/Alert'
import FormField, { inputClass } from '../../components/common/FormField'
import PasswordInput from '../../components/common/PasswordInput'
import SubmitButton from '../../components/common/SubmitButton'
import { friendlyAuthError } from '../../features/authentication/authErrors'
import { toAppError } from '../../services/api/appError'
import { register as registerUser } from '../../services/api/authApi'
import { registerSchema, type RegisterForm } from '../../validations/authSchemas'

// Backend error code -> the form field it belongs to.
const CODE_TO_FIELD: Record<string, keyof RegisterForm> = {
  EMAIL_TAKEN: 'email',
  PHONE_TAKEN: 'phone',
  USERNAME_TAKEN: 'username',
}

const FIELDS: (keyof RegisterForm)[] = ['username', 'displayName', 'email', 'phone', 'password']

export default function RegisterPage() {
  const navigate = useNavigate()
  const [serverError, setServerError] = useState<string | null>(null)

  const {
    register,
    handleSubmit,
    setError,
    formState: { errors, isSubmitting },
  } = useForm<RegisterForm>({
    resolver: zodResolver(registerSchema),
    defaultValues: { username: '', displayName: '', email: '', phone: '', password: '', confirmPassword: '' },
  })

  async function onSubmit(values: RegisterForm) {
    setServerError(null)
    const email = values.email.trim().toLowerCase()
    try {
      await registerUser({
        username: values.username.toLowerCase(),
        displayName: values.displayName.trim(),
        email,
        phone: values.phone ? values.phone : undefined,
        password: values.password,
      })
      // The server has emailed a 6-digit code: ask for it.
      navigate(`/verify?identifier=${encodeURIComponent(email)}`, { state: { codeJustSent: true } })
    } catch (error) {
      const appError = toAppError(error)

      // "email already used" etc. -> show next to that field
      const field = appError.errorCode ? CODE_TO_FIELD[appError.errorCode] : undefined
      if (field) {
        setError(field, { message: friendlyAuthError(appError) })
        return
      }
      // 400 with field messages from the server -> show next to each field
      const shown = FIELDS.filter((name) => appError.fieldErrors[name])
      shown.forEach((name) => setError(name, { message: appError.fieldErrors[name] }))
      if (shown.length === 0) {
        setServerError(friendlyAuthError(appError))
      }
    }
  }

  return (
    <section className="card animate-fade-up p-7 shadow-float sm:p-8">
      <h1 className="mb-5 text-2xl font-bold tracking-tight">Create your account</h1>

      {serverError && (
        <div className="mb-4">
          <Alert type="error">{serverError}</Alert>
        </div>
      )}

      <form onSubmit={handleSubmit(onSubmit)} noValidate>
        <FormField label="Display name" htmlFor="displayName" error={errors.displayName?.message}>
          <input id="displayName" autoComplete="name" className={inputClass} {...register('displayName')} />
        </FormField>

        <FormField
          label="Username"
          htmlFor="username"
          error={errors.username?.message}
          hint="Letters, numbers, _ or . (3-30 characters)"
        >
          <input id="username" autoComplete="username" className={inputClass} {...register('username')} />
        </FormField>

        <FormField label="Email" htmlFor="email" error={errors.email?.message}>
          <input id="email" type="email" autoComplete="email" className={inputClass} {...register('email')} />
        </FormField>

        <FormField
          label="Phone number (optional)"
          htmlFor="phone"
          error={errors.phone?.message}
          hint="International format, e.g. +919876543210"
        >
          <input id="phone" type="tel" autoComplete="tel" className={inputClass} {...register('phone')} />
        </FormField>

        <FormField
          label="Password"
          htmlFor="password"
          error={errors.password?.message}
          hint="At least 8 characters with a letter and a number"
        >
          <PasswordInput id="password" autoComplete="new-password" {...register('password')} />
        </FormField>

        <FormField label="Confirm password" htmlFor="confirmPassword" error={errors.confirmPassword?.message}>
          <PasswordInput id="confirmPassword" autoComplete="new-password" {...register('confirmPassword')} />
        </FormField>

        <SubmitButton loading={isSubmitting}>Create account</SubmitButton>
      </form>

      <p className="mt-4 text-center text-sm">
        Already have an account?{' '}
        <Link to="/login" className="text-brand-600 hover:underline">
          Log in
        </Link>
      </p>
    </section>
  )
}
