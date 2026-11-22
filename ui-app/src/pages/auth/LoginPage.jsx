import { useState } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'
import Alert from '../../components/common/Alert'
import FormField, { inputClass } from '../../components/common/FormField'
import PasswordInput from '../../components/common/PasswordInput'
import SubmitButton from '../../components/common/SubmitButton'
import { loginUser } from '../../features/authentication/authActions'
import { friendlyAuthError } from '../../features/authentication/authErrors'
import { useForm } from '../../hooks/useForm'
import { toAppError } from '../../services/api/appError'
import { useAuthStore } from '../../store/authStore'
import { validateLogin } from '../../validations/authValidation'

// location.state may carry:
//   notice  e.g. "Your email is verified" (sent by the verify / reset pages)
//   from    the page the user wanted before being sent here
export default function LoginPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const accessToken = useAuthStore((state) => state.accessToken)
  const state = location.state
  const [serverError, setServerError] = useState(null)

  const { register, registerCheckbox, handleSubmit, errors, isSubmitting } = useForm({
    initialValues: { identifier: '', password: '', rememberMe: false },
    validate: validateLogin,
  })

  // Already logged in (or just logged in): go to the page the user wanted.
  if (accessToken) {
    return <Navigate to={state?.from?.pathname ?? '/'} replace />
  }

  async function onSubmit(values) {
    setServerError(null)
    try {
      await loginUser(values.identifier.trim(), values.password, values.rememberMe)
      // The store now holds the token, so the redirect above takes over.
    } catch (error) {
      const appError = toAppError(error)
      if (appError.errorCode === 'EMAIL_NOT_VERIFIED') {
        navigate(`/verify?identifier=${encodeURIComponent(values.identifier.trim())}`, {
          state: { notice: 'Please verify your email before logging in.' },
        })
        return
      }
      setServerError(friendlyAuthError(appError))
    }
  }

  return (
    <section className="card animate-fade-up p-7 shadow-float sm:p-8">
      <h1 className="mb-5 text-2xl font-bold tracking-tight">Log in</h1>

      {state?.notice && (
        <div className="mb-4">
          <Alert type="success">{state.notice}</Alert>
        </div>
      )}
      {serverError && (
        <div className="mb-4">
          <Alert type="error">{serverError}</Alert>
        </div>
      )}

      <form onSubmit={handleSubmit(onSubmit)} noValidate>
        <FormField label="Email or phone number" htmlFor="identifier" error={errors.identifier} hint="You can also type your username.">
          <input id="identifier" autoComplete="username" className={inputClass} {...register('identifier')} />
        </FormField>

        <FormField label="Password" htmlFor="password" error={errors.password}>
          <PasswordInput id="password" autoComplete="current-password" {...register('password')} />
        </FormField>

        <div className="mb-4 flex items-center justify-between text-sm">
          <label className="flex items-center gap-2">
            <input type="checkbox" {...registerCheckbox('rememberMe')} />
            Remember me
          </label>
          <Link to="/forgot-password" className="text-brand-600 hover:underline">
            Forgot password?
          </Link>
        </div>

        <SubmitButton loading={isSubmitting}>Log in</SubmitButton>
      </form>

      <p className="mt-4 text-center text-sm">
        New here?{' '}
        <Link to="/register" className="text-brand-600 hover:underline">
          Create an account
        </Link>
      </p>
    </section>
  )
}
