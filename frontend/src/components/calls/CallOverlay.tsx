import { Loader2, Mic, MicOff, Phone, PhoneOff, Video, VideoOff, X } from 'lucide-react'
import { useEffect, useRef, useState, type ReactNode } from 'react'
import Avatar from '../common/Avatar'
import { callEngine } from '../../features/calls/callEngine'
import { callKindLabel, formatDuration } from '../../features/calls/callText'
import { useCallStore } from '../../features/calls/callStore'

// The call screen. It lies over every logged-in page (mounted once in AppLayout) and shows the current call:
//   incoming  -> who calls, Accept / Decline
//   outgoing  -> "Calling...", Cancel
//   connecting / active -> the other person's video (or their picture for a voice call), my own small video,
//                          the timer, Mute, Camera, Hang up
//   ended     -> why it ended ("No answer", "Declined" ...), closes itself after a few seconds
// Phones: full screen. Larger screens: a card in the middle (a big dark one during a video call).
// data-call-state / data-connection-state are read by the end-to-end tests.
export default function CallOverlay() {
  const state = useCallStore((s) => s.state)
  const connectionState = useCallStore((s) => s.connectionState)

  return (
    <div
      data-testid="call-overlay"
      data-call-state={state}
      data-connection-state={connectionState}
      className={state === 'idle' ? 'hidden' : 'fixed inset-0 z-50 flex animate-fade-in items-center justify-center bg-gray-950/70 backdrop-blur-md'}
    >
      {state !== 'idle' && <CallPanel />}
    </div>
  )
}

function CallPanel() {
  const state = useCallStore((s) => s.state)
  const call = useCallStore((s) => s.call)
  const peer = useCallStore((s) => s.peer)
  const type = useCallStore((s) => s.type)
  const localStream = useCallStore((s) => s.localStream)
  const remoteStream = useCallStore((s) => s.remoteStream)
  const remoteVideo = useCallStore((s) => s.remoteVideo)
  const muted = useCallStore((s) => s.muted)
  const cameraOff = useCallStore((s) => s.cameraOff)
  const accepting = useCallStore((s) => s.accepting)
  const error = useCallStore((s) => s.error)
  const endedText = useCallStore((s) => s.endedText)

  const person = peer ?? call?.otherUser ?? null
  const name = person?.displayName ?? ''
  const video = type === 'VIDEO'
  const inCall = state === 'connecting' || state === 'active'
  // The big picture: the other person's video once it arrives (video calls only).
  const showRemoteVideo = video && inCall && remoteStream !== null && remoteVideo

  let status: ReactNode
  switch (state) {
    case 'incoming':
      status = `Incoming ${callKindLabel(type)}...`
      break
    case 'outgoing':
      status = `Calling... (${callKindLabel(type)})`
      break
    case 'connecting':
      status = 'Connecting...'
      break
    case 'active':
      status = <CallTimer since={call?.answeredAt ?? null} />
      break
    case 'ended':
      status = endedText ?? 'Call ended'
      break
    default:
      status = ''
  }

  const panelSize =
    video && inCall
      ? 'h-full w-full sm:h-[80vh] sm:max-w-4xl sm:rounded-2xl'
      : 'h-full w-full sm:h-auto sm:max-w-sm sm:rounded-2xl'

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="call-title"
      className={`relative flex animate-pop flex-col overflow-hidden bg-gradient-to-b from-gray-800 via-gray-900 to-gray-950 text-white shadow-2xl ring-1 ring-white/10 ${panelSize}`}
    >
      {showRemoteVideo && (
        <StreamVideo stream={remoteStream} label={`${name}'s video`} className="absolute inset-0 h-full w-full bg-black object-contain" />
      )}
      {/* A voice call (or a video call before the picture arrives) still needs the other person's sound. */}
      {inCall && remoteStream && !showRemoteVideo && <StreamAudio stream={remoteStream} />}

      <div
        className={`relative z-10 flex flex-1 flex-col items-center gap-3 px-6 pt-12 pb-6 text-center ${
          showRemoteVideo ? 'justify-start bg-gradient-to-b from-black/60 to-transparent' : 'justify-center'
        }`}
      >
        {!showRemoteVideo && person && (
          <span className={`mb-2 rounded-full ${state === 'incoming' || state === 'outgoing' ? 'animate-ring-pulse' : ''}`}>
            <Avatar name={person.displayName} imageUrl={person.profileImageUrl} size={112} />
          </span>
        )}
        <h2 id="call-title" className="text-2xl font-semibold tracking-tight">
          {name}
        </h2>
        <p className="text-sm text-gray-200" aria-live="polite" data-testid="call-status">
          {status}
        </p>
        {state === 'incoming' && error && (
          <p role="alert" className="max-w-xs rounded bg-red-900/70 px-3 py-2 text-sm text-red-100">
            {error}
          </p>
        )}
        {state === 'incoming' && accepting && (
          <p className="flex items-center gap-2 text-sm text-gray-300">
            <Loader2 className="animate-spin" size={16} /> Starting your {video ? 'camera and microphone' : 'microphone'}...
          </p>
        )}
      </div>

      {/* My own video, small, in the corner (mirrored like a mirror, as people expect). */}
      {video && inCall && localStream && !cameraOff && (
        <StreamVideo
          stream={localStream}
          muted
          label="My video"
          className="absolute right-3 bottom-28 z-20 h-32 w-24 -scale-x-100 rounded-2xl border border-white/30 bg-black object-cover shadow-float sm:h-36 sm:w-48"
        />
      )}

      <div className="relative z-10 flex items-center justify-center gap-6 bg-gradient-to-t from-black/70 to-transparent px-6 pt-4 pb-8">
        {state === 'incoming' && (
          <>
            <RoundButton label="Decline" color="red" onClick={() => callEngine.declineIncoming()}>
              <PhoneOff size={26} />
            </RoundButton>
            <RoundButton label="Accept" color="green" disabled={accepting} onClick={() => void callEngine.acceptIncoming()}>
              {video ? <Video size={26} /> : <Phone size={26} />}
            </RoundButton>
          </>
        )}

        {state === 'outgoing' && (
          <RoundButton label="Cancel call" color="red" onClick={() => callEngine.hangUp()}>
            <PhoneOff size={26} />
          </RoundButton>
        )}

        {inCall && (
          <>
            <RoundButton label={muted ? 'Unmute' : 'Mute'} pressed={muted} onClick={() => callEngine.toggleMute()}>
              {muted ? <MicOff size={24} /> : <Mic size={24} />}
            </RoundButton>
            {video && (
              <RoundButton
                label={cameraOff ? 'Turn camera on' : 'Turn camera off'}
                pressed={cameraOff}
                onClick={() => callEngine.toggleCamera()}
              >
                {cameraOff ? <VideoOff size={24} /> : <Video size={24} />}
              </RoundButton>
            )}
            <RoundButton label="Hang up" color="red" onClick={() => callEngine.hangUp()}>
              <PhoneOff size={26} />
            </RoundButton>
          </>
        )}

        {state === 'ended' && (
          <RoundButton label="Close" onClick={() => callEngine.dismiss()}>
            <X size={24} />
          </RoundButton>
        )}
      </div>
    </div>
  )
}

const COLORS = {
  red: 'bg-red-500 shadow-[0_6px_22px_rgb(239_68_68/0.4)] hover:bg-red-600',
  green: 'bg-emerald-500 shadow-[0_6px_22px_rgb(16_185_129/0.45)] hover:bg-emerald-600',
  plain: 'bg-white/15 backdrop-blur hover:bg-white/25',
}

function RoundButton({
  label,
  color = 'plain',
  pressed,
  disabled,
  onClick,
  children,
}: {
  label: string
  color?: keyof typeof COLORS
  pressed?: boolean
  disabled?: boolean
  onClick: () => void
  children: ReactNode
}) {
  return (
    <button
      type="button"
      aria-label={label}
      title={label}
      aria-pressed={pressed}
      disabled={disabled}
      onClick={onClick}
      className={`flex h-16 w-16 items-center justify-center rounded-full text-white transition hover:scale-105 disabled:cursor-not-allowed disabled:opacity-50 ${
        pressed ? 'bg-white text-gray-900 hover:bg-gray-200' : COLORS[color]
      }`}
    >
      {children}
    </button>
  )
}

// The running "0:42" of an active call. It counts from the moment the call was answered (the server's time); if this
// computer's clock is ahead of the server, it counts from when the timer appeared instead, so it never starts negative.
function CallTimer({ since }: { since: string | null }) {
  const [shownAt] = useState(() => Date.now())
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [])
  const answered = since ? Date.parse(since) : Number.NaN
  const start = Number.isNaN(answered) ? shownAt : Math.min(answered, shownAt)
  return <span data-testid="call-timer">{formatDuration((now - start) / 1000)}</span>
}

// A <video> showing a MediaStream (React cannot set srcObject as an attribute, so a ref does it).
function StreamVideo({
  stream,
  muted = false,
  label,
  className,
}: {
  stream: MediaStream
  muted?: boolean
  label: string
  className: string
}) {
  const ref = useRef<HTMLVideoElement>(null)
  useEffect(() => {
    const element = ref.current
    if (!element) return
    element.muted = muted // my own video must be silent, or I would hear myself
    if (element.srcObject !== stream) element.srcObject = stream
  }, [stream, muted])
  return <video ref={ref} autoPlay playsInline muted={muted} aria-label={label} className={className} />
}

// The other person's sound during a voice call (an invisible <audio>).
function StreamAudio({ stream }: { stream: MediaStream }) {
  const ref = useRef<HTMLAudioElement>(null)
  useEffect(() => {
    const element = ref.current
    if (element && element.srcObject !== stream) element.srcObject = stream
  }, [stream])
  return <audio ref={ref} autoPlay data-testid="remote-audio" />
}
