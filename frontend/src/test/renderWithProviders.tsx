import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import type { ReactElement } from 'react'
import { MemoryRouter } from 'react-router-dom'

// Renders `ui` (usually a <Routes>) with a router and a fresh TanStack Query cache.
export function renderWithProviders(ui: ReactElement, options: { route?: string; state?: unknown } = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const [pathname, search] = (options.route ?? '/').split('?')
  const entry = { pathname, search: search ? `?${search}` : '', state: options.state }

  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[entry]}>{ui}</MemoryRouter>
    </QueryClientProvider>,
  )
}

// A login/refresh answer that tests can reuse.
export function fakeTokens(overrides: Partial<{ accessToken: string; refreshToken: string }> = {}) {
  return {
    accessToken: 'access-1',
    refreshToken: 'refresh-1',
    tokenType: 'Bearer' as const,
    expiresIn: 900,
    user: { id: 'u1', username: 'asha', displayName: 'Asha Rao', email: 'asha@example.com' },
    ...overrides,
  }
}
