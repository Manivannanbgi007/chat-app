import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { AppError } from '../../services/api/appError'
import { login } from '../../services/api/authApi'
import { useAuthStore } from '../../store/authStore'
import LocationDisplay from '../../test/LocationDisplay'
import { fakeTokens, renderWithProviders } from '../../test/renderWithProviders'
import LoginPage from './LoginPage'

vi.mock('../../services/api/authApi')
const mockedLogin = vi.mocked(login)

function renderLogin() {
  renderWithProviders(
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="*" element={<LocationDisplay />} />
    </Routes>,
    { route: '/login' },
  )
}

async function fillAndSubmit(identifier = 'asha@example.com', password = 'Secret123') {
  const user = userEvent.setup()
  await user.type(screen.getByLabelText('Email or phone number'), identifier)
  await user.type(screen.getByLabelText('Password'), password)
  return user
}

describe('LoginPage', () => {
  beforeEach(() => {
    mockedLogin.mockReset()
    useAuthStore.getState().clearSession()
  })

  it('shows validation messages when the form is empty', async () => {
    renderLogin()
    await userEvent.setup().click(screen.getByRole('button', { name: 'Log in' }))

    expect(await screen.findByText('Enter your email or phone number')).toBeInTheDocument()
    expect(screen.getByText('Enter your password')).toBeInTheDocument()
    expect(mockedLogin).not.toHaveBeenCalled()
  })

  it('logs in, keeps the session and leaves the login page', async () => {
    mockedLogin.mockResolvedValue(fakeTokens())
    renderLogin()
    const user = await fillAndSubmit()
    await user.click(screen.getByRole('button', { name: 'Log in' }))

    expect(await screen.findByTestId('path')).toHaveTextContent('/')
    expect(mockedLogin).toHaveBeenCalledWith(expect.objectContaining({ identifier: 'asha@example.com', password: 'Secret123' }))
    expect(useAuthStore.getState().accessToken).toBe('access-1')
    expect(sessionStorage.length).toBe(1) // "Remember me" was not ticked
  })

  it('"Remember me" keeps the refresh token in localStorage', async () => {
    mockedLogin.mockResolvedValue(fakeTokens())
    renderLogin()
    const user = await fillAndSubmit()
    await user.click(screen.getByLabelText('Remember me'))
    await user.click(screen.getByRole('button', { name: 'Log in' }))

    await screen.findByTestId('path')
    expect(localStorage.length).toBe(1)
  })

  it('sends an unverified user to the verify page', async () => {
    mockedLogin.mockRejectedValue(new AppError('FORBIDDEN', 'Email not verified', 403, {}, 'EMAIL_NOT_VERIFIED'))
    renderLogin()
    const user = await fillAndSubmit('asha@example.com')
    await user.click(screen.getByRole('button', { name: 'Log in' }))

    expect(await screen.findByTestId('path')).toHaveTextContent('/verify?identifier=asha%40example.com')
    expect(useAuthStore.getState().accessToken).toBeNull()
  })

  it('shows a friendly message for wrong credentials', async () => {
    mockedLogin.mockRejectedValue(new AppError('UNAUTHORIZED', 'x', 401, {}, 'INVALID_CREDENTIALS'))
    renderLogin()
    const user = await fillAndSubmit()
    await user.click(screen.getByRole('button', { name: 'Log in' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Incorrect email/phone or password.')
  })

  it('shows a message when the account is locked', async () => {
    mockedLogin.mockRejectedValue(new AppError('UNKNOWN', 'x', 423, {}, 'ACCOUNT_LOCKED'))
    renderLogin()
    const user = await fillAndSubmit()
    await user.click(screen.getByRole('button', { name: 'Log in' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('locked for 15 minutes')
  })

  it('can show and hide the password', async () => {
    renderLogin()
    const user = userEvent.setup()
    const input = screen.getByLabelText('Password')
    expect(input).toHaveAttribute('type', 'password')

    await user.click(screen.getByRole('button', { name: 'Show password' }))
    expect(input).toHaveAttribute('type', 'text')
  })
})
