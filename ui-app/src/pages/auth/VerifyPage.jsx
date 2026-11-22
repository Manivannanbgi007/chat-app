import { useState } from 'react'
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import Alert from '../../components/common/Alert'
import FormField, { inputClass } from '../../components/common/FormField'
import SubmitButton from '../../components/common/SubmitButton'
import { friendlyAuthError } from '../../features/authentication/authErrors'
import { useCountdown } from '../../hooks/useCountdown'
import { useForm } from '../../hooks/useForm'
import { toAppError } from '../../services/api/appError'
import { resendOtp, verifyOtp } from '../../services/api/authApi'
import { validateVerify } from '../../validations/authValidation'

const RESEND_WAIT_SECONDS = 60

// location.state may carry:
//   notice        a message to show at the top
//   codeJustSent  true when we come straight from the register page
//
// "Verify email" and "OTP verification" are the same step: type the 6-digit code from the email.
export default function VerifyPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const [params] = useSearchParams()
  const state = location.state
  const identifierFromUrl = params.get('identifier') ?? ''

  const [error, setError] = useState(null)
  const [info, setInfo] = useState(state?.notice ?? null)
  const [resending, setResending] = useState(false)

  // A code was just emailed, so the server will refuse a new one for the next 60 seconds.
  const { secondsLeft, start } = useCountdown(state?.codeJustSent ? RESEND_WAIT_SECONDS : 0)

  // The identifier from the URL is part of the form values even when its input is not shown.
  const { register, handleSubmit, getValues, errors, isSubmitting } = useForm({
    initialValues: { identifier: identifierFromUrl, otp: '' },
    validate: validateVerify,
  })

  async function onSubmit(values) {
    setError(null)
    setInfo(null)
    try {
      await verifyOtp({ identifier: values.identifier.trim(), otp: values.otp })
      navigate('/login', { state: { notice: 'Your email is verified. You can log in now.' } })
    } catch (e) {
      setError(friendlyAuthError(toAppError(e)))
    }
  }

  async function handleResend() {
    const identifier = getValues('identifier').trim()
    if (!identifier) {
      setError('Enter your email first.')
      return
    }
    setError(null)
    setInfo(null)
    setResending(true)
    try {
      await resendOtp(identifier)
      setInfo('If the account exists, a new code is on its way.')
      start(RESEND_WAIT_SECONDS)
    } catch (e) {
      const appError = toAppError(e)
      setError(friendlyAuthError(appError))
      if (appError.errorCode === 'OTP_RATE_LIMITED') {
        start(RESEND_WAIT_SECONDS)
      }
    } finally {
      setResending(false)
    }
  }

  return (
    <section className="card animate-fade-up p-7 shadow-float sm:p-8">
      <h1 className="mb-2 text-2xl font-bold tracking-tight">Verify your email</h1>
      <p className="mb-4 text-sm text-gray-600 dark:text-gray-300">
        {identifierFromUrl ? (
          <>
            We sent a 6-digit code to <strong>{identifierFromUrl}</strong>. It is valid for 10 minutes.
          </>
        ) : (
          'Enter the email you registered with and the 6-digit code we sent you.'
        )}
      </p>

      {info && (
        <div className="mb-4">
          <Alert type="info">{info}</Alert>
        </div>
      )}
      {error && (
        <div className="mb-4">
          <Alert type="error">{error}</Alert>
        </div>
      )}

      <form onSubmit={handleSubmit(onSubmit)} noValidate>
        {/* The identifier only needs to be typed when we did not get it from the register page */}
        {!identifierFromUrl && (
          <FormField label="Email" htmlFor="identifier" error={errors.identifier}>
            <input id="identifier" type="email" autoComplete="email" className={inputClass} {...register('identifier')} />
          </FormField>
        )}

        <FormField label="Verification code" htmlFor="otp" error={errors.otp}>
          <input
            id="otp"
            inputMode="numeric"
            autoComplete="one-time-code"
            maxLength={6}
            className={`${inputClass} text-center text-xl tracking-widest`}
            {...register('otp')}
          />
        </FormField>

        <SubmitButton loading={isSubmitting}>Verify</SubmitButton>
      </form>

      <div className="mt-4 text-center text-sm">
        <button
          type="button"
          onClick={handleResend}
          disabled={secondsLeft > 0 || resending}
          className="text-brand-600 hover:underline disabled:text-gray-400 disabled:no-underline"
        >
          {secondsLeft > 0 ? `Resend code in ${secondsLeft}s` : 'Resend code'}
        </button>
      </div>

      <p className="mt-4 text-center text-sm">
        <Link to="/login" className="text-brand-600 hover:underline">
          Back to log in
        </Link>
      </p>
    </section>
  )
}
