import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { AppError } from '../services/api/appError'
import { pingAuthService } from '../services/api/authApi'
import SystemStatusPage from './SystemStatusPage'

// Replace the real API call with a fake one.
vi.mock('../services/api/authApi')
const mockedPing = vi.mocked(pingAuthService)

function renderPage() {
  const queryClient = new QueryClient()
  render(
    <QueryClientProvider client={queryClient}>
      <SystemStatusPage />
    </QueryClientProvider>,
  )
}

describe('SystemStatusPage', () => {
  it('shows "Connected" when the backend answers', async () => {
    mockedPing.mockResolvedValue({ service: 'auth-service', status: 'UP' })
    renderPage()
    expect(await screen.findByText(/Connected: auth-service is UP/)).toBeInTheDocument()
  })

  it('shows "Backend unreachable" and the reason when the call fails', async () => {
    mockedPing.mockRejectedValue(new AppError('NETWORK', 'Cannot reach the server.'))
    renderPage()
    expect(await screen.findByText('Backend unreachable')).toBeInTheDocument()
    expect(screen.getByText('Cannot reach the server.')).toBeInTheDocument()
  })

  it('shows a loading message first', () => {
    mockedPing.mockReturnValue(new Promise(() => {})) // never finishes
    renderPage()
    expect(screen.getByText(/Checking the backend/)).toBeInTheDocument()
  })
})
