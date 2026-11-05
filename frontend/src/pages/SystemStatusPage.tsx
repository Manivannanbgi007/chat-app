import { useQuery } from '@tanstack/react-query'
import { CheckCircle2, Loader2, XCircle } from 'lucide-react'
import { pingAuthService } from '../services/api/authApi'

// Phase 1 page (public, /system-status): proves that browser -> gateway -> auth-service works.
// (It lived at /status until Phase 8, when /status became the status / stories page.)
export default function SystemStatusPage() {
  const { data, error, isPending, isFetching, refetch } = useQuery({
    queryKey: ['auth-ping'],
    queryFn: pingAuthService,
    retry: false, // show the failure right away instead of retrying silently
  })

  return (
    <section className="card animate-fade-up p-7 shadow-float sm:p-8">
      <h1 className="mb-4 text-xl font-semibold">System status</h1>

      {isPending && (
        <p className="flex items-center gap-2">
          <Loader2 className="animate-spin" size={20} /> Checking the backend...
        </p>
      )}

      {data && (
        <p className="flex items-center gap-2 text-brand-600">
          <CheckCircle2 size={20} />
          Connected: {data.service} is {data.status}
        </p>
      )}

      {error && (
        <div>
          <p className="flex items-center gap-2 text-red-600">
            <XCircle size={20} />
            Backend unreachable
          </p>
          <p className="mt-1 text-sm text-gray-600 dark:text-gray-300">{error.message}</p>
        </div>
      )}

      <button
        type="button"
        onClick={() => refetch()}
        disabled={isFetching}
        className="mt-4 rounded bg-brand-600 px-3 py-1 text-white hover:bg-brand-700 disabled:opacity-50"
      >
        Check again
      </button>
    </section>
  )
}
