import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { useAuthStore } from '../store/authStore'
import ProtectedRoute from './ProtectedRoute'

// Shows which page the user was sent back from (stored in the router state).
function LoginPage() {
  const location = useLocation()
  const from = (location.state as { from?: { pathname: string } } | null)?.from?.pathname
  return <p>Login page, wanted: {from ?? 'nothing'}</p>
}

function renderAt(path: string) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route element={<ProtectedRoute />}>
          <Route path="/secret" element={<p>Secret page</p>} />
        </Route>
      </Routes>
    </MemoryRouter>,
  )
}

describe('ProtectedRoute', () => {
  beforeEach(() => useAuthStore.setState({ accessToken: null, restoring: false }))

  it('redirects to /login when there is no token', () => {
    renderAt('/secret')
    expect(screen.getByText(/Login page/)).toBeInTheDocument()
  })

  it('remembers the page the user wanted', () => {
    renderAt('/secret')
    expect(screen.getByText('Login page, wanted: /secret')).toBeInTheDocument()
  })

  it('shows the page when a token exists', () => {
    useAuthStore.setState({ accessToken: 'abc' })
    renderAt('/secret')
    expect(screen.getByText('Secret page')).toBeInTheDocument()
  })

  it('shows a loading screen (and does not redirect) while the session is being restored', () => {
    useAuthStore.setState({ restoring: true })
    renderAt('/secret')
    expect(screen.getByRole('status')).toHaveTextContent('Loading')
    expect(screen.queryByText(/Login page/)).not.toBeInTheDocument()
  })
})
