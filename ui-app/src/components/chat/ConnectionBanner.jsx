import { WifiOff } from 'lucide-react'
import { useConnectionState } from '../../services/websocket/useConnectionState'

// A thin bar at the top while the live connection is down. New messages are not lost:
// they are fetched as soon as the connection is back.
export default function ConnectionBanner() {
  const state = useConnectionState()
  if (state !== 'reconnecting') return null

  return (
    <div role="status" className="flex items-center justify-center gap-2 bg-amber-100 px-3 py-1 text-sm text-amber-900">
      <WifiOff size={16} /> Reconnecting...
    </div>
  )
}
