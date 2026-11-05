import { screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { Route, Routes } from 'react-router-dom'
import { AppError } from '../../services/api/appError'
import { register } from '../../services/api/authApi'
import LocationDisplay from '../../test/LocationDisplay'
import { renderWithProviders } from '../../test/renderWithProviders'
import RegisterPage from './RegisterPage'

vi.mock('../../services/api/authApi')
const mockedRegister = vi.mocked(register)

function renderRegister() {
  renderWithProviders(
    <Routes>
      <Route path="/register" element={<RegisterPage />} />
      <Route path="*" element={<LocationDisplay />} />
    </Routes>,
    { route: '/register' },
  )
}

async function fill(values: Partial<Record<string, string>> = {}) {
  const user = userEvent.setup()
  const data = {
    'Display name': 'Asha Rao',
    Username: 'Asha.Rao',
    Email: 'Asha@Example.com',
    Password: 'Secret123',
    'Confirm password': 'Secret123',
    ...values,
  }
  for (const [label, value] of Object.entries(data)) {
    if (value) await user.type(screen.getByLabelText(label), value)
  }
  return user
}

describe('RegisterPage', () => {
  beforeEach(() => {
    mockedRegister.mockReset()
  })

  it('rejects weak or mismatching input before calling the server', async () => {
    renderRegister()
    const user = await fill({
      Username: 'a!',
      Email: 'not-an-email',
      Password: 'short',
      'Confirm password': 'different',
    })
    await user.type(screen.getByLabelText('Phone number (optional)'), '12345')
    await user.click(screen.getByRole('button', { name: 'Create account' }))

    expect(await screen.findByText(/3-30 characters/)).toBeInTheDocument()
    expect(screen.getByText('Enter a valid email address')).toBeInTheDocument()
    expect(screen.getByText('Password must be at least 8 characters')).toBeInTheDocument()
    expect(screen.getByText('Passwords do not match')).toBeInTheDocument()
    expect(screen.getByText(/International format|Use international format/)).toBeInTheDocument()
    expect(mockedRegister).not.toHaveBeenCalled()
  })

  it('requires a letter and a number in the password', async () => {
    renderRegister()
    const user = await fill({ Password: 'onlyletters', 'Confirm password': 'onlyletters' })
    await user.click(screen.getByRole('button', { name: 'Create account' }))

    expect(await screen.findByText('Password must contain a number')).toBeInTheDocument()
  })

  it('registers with a lowercase username/email and goes to the verify page', async () => {
    mockedRegister.mockResolvedValue({ userId: 'u1', email: 'asha@example.com', verificationRequired: true })
    renderRegister()
    const user = await fill()
    await user.click(screen.getByRole('button', { name: 'Create account' }))

    expect(await screen.findByTestId('path')).toHaveTextContent('/verify?identifier=asha%40example.com')
    expect(screen.getByTestId('state')).toHaveTextContent('"codeJustSent":true')
    expect(mockedRegister).toHaveBeenCalledWith({
      username: 'asha.rao',
      displayName: 'Asha Rao',
      email: 'asha@example.com',
      phone: undefined,
      password: 'Secret123',
    })
  })

  it('shows "email already used" next to the email field', async () => {
    mockedRegister.mockRejectedValue(new AppError('UNKNOWN', 'Email exists', 409, {}, 'EMAIL_TAKEN'))
    renderRegister()
    const user = await fill()
    await user.click(screen.getByRole('button', { name: 'Create account' }))

    expect(await screen.findByText('An account with this email already exists.')).toBeInTheDocument()
  })
})
