import { QueryClientProvider } from '@tanstack/react-query'
import { useEffect } from 'react'
import { BrowserRouter } from 'react-router-dom'
import { restoreSession } from './features/authentication/authActions'
import AppRoutes from './routes/AppRoutes'
import { queryClient } from './services/queryClient'

export default function App() {
  // On start-up, if the user chose "Remember me" earlier, log them in again silently.
  useEffect(() => {
    void restoreSession()
  }, [])

  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <AppRoutes />
      </BrowserRouter>
    </QueryClientProvider>
  )
}
