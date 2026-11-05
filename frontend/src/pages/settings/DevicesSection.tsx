import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Loader2 } from 'lucide-react'
import Alert from '../../components/common/Alert'
import { rowButtonClass } from '../../components/contacts/UserRow'
import { toAppError } from '../../services/api/appError'
import { deleteSession, getSessions } from '../../services/api/authApi'
import { useAuthStore } from '../../store/authStore'
import type { SessionInfo } from '../../types/auth'

function formatDate(iso: string): string {
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? iso : date.toLocaleString()
}

// "Active devices": every browser/phone that is logged in to this account.
export default function DevicesSection() {
  const queryClient = useQueryClient()
  const sessionsQuery = useQuery({ queryKey: ['sessions'], queryFn: getSessions })

  const endMutation = useMutation({
    mutationFn: (session: SessionInfo) => deleteSession(session.id),
    onSuccess: (_result, session) => {
      if (session.current) {
        // I ended my own session: this device is logged out (ProtectedRoute shows the login page).
        useAuthStore.getState().clearSession()
      } else {
        void queryClient.invalidateQueries({ queryKey: ['sessions'] })
      }
    },
  })

  return (
    <section className="card p-6">
      <h2 className="mb-4 text-lg font-semibold">Active devices</h2>

      {sessionsQuery.isPending && (
        <p className="flex items-center gap-2">
          <Loader2 className="animate-spin" size={20} /> Loading...
        </p>
      )}
      {sessionsQuery.error && <Alert type="error">{toAppError(sessionsQuery.error).message}</Alert>}
      {endMutation.error && (
        <div className="mb-3">
          <Alert type="error">{toAppError(endMutation.error).message}</Alert>
        </div>
      )}

      {sessionsQuery.data && (
        <ul>
          {sessionsQuery.data.map((session) => (
            <li
              key={session.id}
              className="flex flex-wrap items-center justify-between gap-2 border-b border-gray-100 py-3 last:border-0 dark:border-gray-700"
            >
              <div>
                <p className="font-medium">
                  {session.deviceName ?? 'Unknown device'}
                  {session.current && (
                    <span className="ml-2 rounded bg-brand-100 px-2 py-0.5 text-xs text-brand-700 dark:bg-brand-900 dark:text-brand-200">
                      This device
                    </span>
                  )}
                </p>
                <p className="text-sm text-gray-500 dark:text-gray-400">
                  {session.ipAddress ? `${session.ipAddress} - ` : ''}last active {formatDate(session.lastActiveAt)}
                </p>
              </div>
              <button
                type="button"
                onClick={() => endMutation.mutate(session)}
                disabled={endMutation.isPending}
                className={rowButtonClass}
              >
                {session.current ? 'Log out this device' : 'End session'}
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
