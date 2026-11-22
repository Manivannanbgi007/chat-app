import { Phone, Video } from 'lucide-react'
import { callEngine } from '../../features/calls/callEngine'
import { isBusyState, useCallStore } from '../../features/calls/callStore'

// The two call buttons (voice, video) in the header of a private chat. Disabled while a call is running.
// props: { user } (user: a PublicProfile, i.e. { id, displayName, profileImageUrl, ... })
export default function CallButtons({ user }) {
  const busy = useCallStore((state) => isBusyState(state.state))
  const buttonClass =
    'rounded p-1 text-gray-500 hover:bg-gray-100 disabled:cursor-not-allowed disabled:opacity-40 dark:text-gray-300 dark:hover:bg-gray-700'

  return (
    <>
      <button
        type="button"
        aria-label="Voice call"
        title={`Voice call with ${user.displayName}`}
        disabled={busy}
        onClick={() => callEngine.startCall(user, 'AUDIO')}
        className={buttonClass}
      >
        <Phone size={20} />
      </button>
      <button
        type="button"
        aria-label="Video call"
        title={`Video call with ${user.displayName}`}
        disabled={busy}
        onClick={() => callEngine.startCall(user, 'VIDEO')}
        className={buttonClass}
      >
        <Video size={20} />
      </button>
    </>
  )
}
