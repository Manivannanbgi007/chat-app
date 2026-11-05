import { QueryClient } from '@tanstack/react-query'
import { AppError } from './api/appError'

// One shared cache for all server data (TanStack Query).
// It lives in its own file so the auth store can empty it when the user logs out
// (otherwise the next person on the same browser could briefly see old data).
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      // Retry only when the problem may go away by itself (server hiccup, no network).
      retry: (failureCount, error) =>
        failureCount < 2 && error instanceof AppError && (error.kind === 'NETWORK' || error.kind === 'SERVER'),
      refetchOnWindowFocus: false,
    },
  },
})
