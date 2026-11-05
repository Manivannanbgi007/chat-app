import { act, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { AppError } from '../../services/api/appError'
import { resendOtp, verifyOtp } from '../../services/api/authApi'
import LocationDisplay from '../../test/LocationDisplay'
import { renderWithProviders } from '../../test/renderWithProviders'
import VerifyPage from './VerifyPage'

vi.mock('../../services/api/authApi')
const mockedVerify = vi.mocked(verifyOtp)
const mockedResend = vi.mocked(resendOtp)

function renderVerify(state?: unknown) {
  renderWithProviders(
    <Routes>
      <Route path="/verify" element={<VerifyPage />} />
      <Route path="*" element={<LocationDisplay />} />
    </Routes>,
    { route: '/verify?identifier=asha@example.com', state },
  )
}

describe('VerifyPage', () => {
  beforeEach(() => {
    mockedVerify.mockReset()
    mockedResend.mockReset()
    // Only the interval is fake, so the countdown can be fast-forwarded while user-event keeps working.
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
  })
  afterEach(() => vi.useRealTimers())

  it('verifies the code and goes to the login page with a success note', async () => {
    mockedVerify.mockResolvedValue()
    renderVerify()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('Verification code'), '123456')
    await user.click(screen.getByRole('button', { name: 'Verify' }))

    expect(await screen.findByTestId('path')).toHaveTextContent('/login')
    expect(screen.getByTestId('state')).toHaveTextContent('Your email is verified')
    expect(mockedVerify).toHaveBeenCalledWith({ identifier: 'asha@example.com', otp: '123456' })
  })

  it('does not call the server for a code that is not 6 digits', async () => {
    renderVerify()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('Verification code'), '12ab')
    await user.click(screen.getByRole('button', { name: 'Verify' }))

    expect(await screen.findByText('Enter the 6-digit code')).toBeInTheDocument()
    expect(mockedVerify).not.toHaveBeenCalled()
  })

  it('shows a message for a wrong code', async () => {
    mockedVerify.mockRejectedValue(new AppError('VALIDATION', 'x', 400, {}, 'INVALID_OTP'))
    renderVerify()
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('Verification code'), '111111')
    await user.click(screen.getByRole('button', { name: 'Verify' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('not correct or has expired')
  })

  it('after registering, resend is locked for 60 seconds and then works', async () => {
    mockedResend.mockResolvedValue()
    renderVerify({ codeJustSent: true })
    const user = userEvent.setup()

    const resend = screen.getByRole('button', { name: /Resend code/ })
    expect(resend).toHaveTextContent('Resend code in 60s')
    expect(resend).toBeDisabled()

    act(() => vi.advanceTimersByTime(30_000))
    expect(resend).toHaveTextContent('Resend code in 30s')

    act(() => vi.advanceTimersByTime(30_000))
    expect(resend).toHaveTextContent('Resend code')
    expect(resend).toBeEnabled()

    await user.click(resend)

    expect(mockedResend).toHaveBeenCalledWith('asha@example.com')
    expect(await screen.findByText(/new code is on its way/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Resend code/ })).toHaveTextContent('Resend code in 60s') // countdown restarted
  })

  it('restarts the countdown when the server says we asked too often', async () => {
    mockedResend.mockRejectedValue(new AppError('RATE_LIMITED', 'x', 429, {}, 'OTP_RATE_LIMITED'))
    renderVerify() // coming from the login page: no countdown yet
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: 'Resend code' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Too many codes requested')
    expect(screen.getByRole('button', { name: /Resend code/ })).toBeDisabled()
  })
})
