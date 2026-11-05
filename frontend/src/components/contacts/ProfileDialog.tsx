import { useQuery } from '@tanstack/react-query'
import { Loader2, X } from 'lucide-react'
import Alert from '../common/Alert'
import Avatar from '../common/Avatar'
import { toAppError } from '../../services/api/appError'
import { getUserProfile } from '../../services/api/userApi'

// Small pop-up with another user's public profile.
// "about" and the photo are null when that person's privacy settings hide them from me.
export default function ProfileDialog({ userId, onClose }: { userId: string; onClose: () => void }) {
  const { data, error, isPending } = useQuery({
    queryKey: ['user', userId],
    queryFn: () => getUserProfile(userId),
  })

  return (
    <div className="fixed inset-0 z-10 flex items-center justify-center bg-gray-950/55 backdrop-blur-sm animate-fade-in p-4">
      <div role="dialog" aria-modal="true" aria-label="User profile" className="w-full max-w-sm animate-pop rounded-2xl bg-white p-6 shadow-float dark:bg-gray-900">
        <div className="mb-4 flex justify-end">
          <button type="button" onClick={onClose} aria-label="Close" className="rounded p-1 hover:bg-gray-100 dark:hover:bg-gray-800">
            <X size={20} />
          </button>
        </div>

        {isPending && (
          <p className="flex items-center justify-center gap-2">
            <Loader2 className="animate-spin" size={20} /> Loading...
          </p>
        )}
        {error && <Alert type="error">{toAppError(error).message}</Alert>}
        {data && (
          <div className="text-center">
            <div className="mb-3 flex justify-center">
              <Avatar name={data.displayName} imageUrl={data.profileImageUrl} size={80} />
            </div>
            <h2 className="text-lg font-semibold">{data.displayName}</h2>
            <p className="mb-3 text-sm text-gray-500 dark:text-gray-400">@{data.username}</p>
            <p className="text-sm">{data.about ?? <span className="text-gray-500 dark:text-gray-400">No about info to show.</span>}</p>
          </div>
        )}
      </div>
    </div>
  )
}
