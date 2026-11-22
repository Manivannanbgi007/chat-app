import { Loader2 } from 'lucide-react'
import Brand from './Brand'

// Full-page loader, shown while the app checks whether the user is still logged in.
export default function LoadingScreen() {
  return (
    <div role="status" className="bg-aurora flex h-dvh flex-col items-center justify-center gap-5">
      <div className="animate-float">
        <Brand size={64} />
      </div>
      <p className="flex items-center gap-2 text-sm text-gray-500 dark:text-gray-400">
        <Loader2 className="animate-spin" size={18} />
        Loading...
      </p>
    </div>
  )
}
