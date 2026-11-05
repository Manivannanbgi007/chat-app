import { ChevronLeft, ChevronRight, Eye, Pause, Play, Trash2, X } from 'lucide-react'
import { useEffect, useReducer, useRef, useState } from 'react'
import Avatar from '../common/Avatar'
import ConfirmDialog from '../common/ConfirmDialog'
import StatusViewersDialog from './StatusViewersDialog'
import { formatStatusTime } from '../../features/status/statusRules'
import {
  currentFraction,
  currentStatus,
  initialViewerState,
  viewerReducer,
  type ViewerGroup,
} from '../../features/status/storyViewerState'
import { useStatusMediaUrl } from '../../features/status/useStatusMediaUrl'
import { toAppError } from '../../services/api/appError'
import { deleteStatus, markStatusViewed } from '../../services/api/statusApi'
import type { Status } from '../../types/status'

const TICK_MS = 100
const HOLD_MS = 300 // pressing longer than this is a "hold" (pause), not a tap

// The full-screen story viewer (on every screen size).
// - bars at the top: one per status of this person; the current one fills up (5 s for text and photos,
//   the length of the video for videos)
// - right half / Next / arrow right = next status (then the next person, then it closes); left half / Previous /
//   arrow left = previous; holding the pointer down or Space pauses; Escape or X closes
// - every status of somebody else is marked as viewed once, when it is shown (never my own)
// - my own statuses show the viewer count (opens the list) and a delete button
export default function StoryViewer({
  groups,
  startGroup,
  startIndex,
  onViewed,
  onDeleted,
  onClose,
}: {
  groups: ViewerGroup[]
  startGroup: number
  startIndex: number
  onViewed: (statusId: string) => void
  onDeleted: (statusId: string) => void
  onClose: () => void
}) {
  const [state, dispatch] = useReducer(viewerReducer, undefined, () => initialViewerState(groups, startGroup, startIndex))
  const [held, setHeld] = useState(false)
  const [spacePaused, setSpacePaused] = useState(false)
  const [viewersOpen, setViewersOpen] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const closeRef = useRef<HTMLButtonElement>(null)
  const markedViewed = useRef(new Set<string>())
  const holdTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const holdHappened = useRef(false)

  const group = state.groups[state.group]
  const status = currentStatus(state)
  const statusId = status?.id
  const paused = held || spacePaused || viewersOpen || confirmDelete || deleting

  // The parent removes the viewer when it closes (last status, Escape, X, everything deleted).
  useEffect(() => {
    if (state.closed) onClose()
  }, [state.closed, onClose])

  // Focus the X when the viewer opens, and give the focus back when it closes.
  useEffect(() => {
    const previouslyFocused = document.activeElement as HTMLElement | null
    closeRef.current?.focus()
    return () => previouslyFocused?.focus?.()
  }, [])

  // The timer of texts and photos (videos move on when they end).
  const runsTimer = state.ready && !paused && (status?.type !== 'VIDEO' || state.failed)
  useEffect(() => {
    if (!runsTimer) return
    const timer = setInterval(() => dispatch({ type: 'TICK', ms: TICK_MS }), TICK_MS)
    return () => clearInterval(timer)
  }, [runsTimer, statusId])

  // "I saw it": once per status, never for my own.
  const isMine = group?.mine ?? false
  const alreadyViewed = status?.viewedByMe ?? true
  useEffect(() => {
    if (!statusId || isMine || alreadyViewed || markedViewed.current.has(statusId)) return
    markedViewed.current.add(statusId)
    markStatusViewed(statusId).catch(() => undefined) // not critical: the next view (or poll) shows the truth
    onViewed(statusId)
  }, [statusId, isMine, alreadyViewed, onViewed])

  // Keyboard: arrows, Space, Escape. The two small dialogs inside handle Escape first.
  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        if (confirmDelete) setConfirmDelete(false)
        else if (viewersOpen) setViewersOpen(false)
        else dispatch({ type: 'CLOSE' })
        return
      }
      if (viewersOpen || confirmDelete) return
      if (event.key === 'ArrowRight') dispatch({ type: 'NEXT' })
      else if (event.key === 'ArrowLeft') dispatch({ type: 'PREVIOUS' })
      else if (event.key === ' ') {
        event.preventDefault()
        setSpacePaused((value) => !value)
      }
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [viewersOpen, confirmDelete])

  // Pressing on the left / right half: a short press is a tap (previous / next), a long one pauses until released.
  function pressStart() {
    holdHappened.current = false
    holdTimer.current = setTimeout(() => {
      holdHappened.current = true
      setHeld(true)
    }, HOLD_MS)
  }
  function pressEnd() {
    if (holdTimer.current) clearTimeout(holdTimer.current)
    holdTimer.current = null
    setHeld(false)
  }
  function tap(action: 'NEXT' | 'PREVIOUS') {
    if (holdHappened.current) {
      holdHappened.current = false // that press was a hold: do not also move on
      return
    }
    dispatch({ type: action })
  }

  async function confirmDeletion() {
    if (!statusId) return
    setDeleting(true)
    setError(null)
    try {
      await deleteStatus(statusId)
      setConfirmDelete(false)
      onDeleted(statusId)
      dispatch({ type: 'REMOVE', statusId })
    } catch (caught) {
      setError(toAppError(caught).message)
      setConfirmDelete(false)
    } finally {
      setDeleting(false)
    }
  }

  if (!group || !status) return null
  const ownerName = group.mine ? 'My status' : group.owner.displayName
  const fraction = currentFraction(state)
  const viewCount = 'viewCount' in status ? status.viewCount : 0

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={`Status of ${ownerName}`}
      data-testid="story-viewer"
      data-status-id={status.id}
      data-paused={paused}
      className="fixed inset-0 z-50 flex flex-col bg-black text-white"
    >
      {/* bars, owner, time and buttons */}
      <div className="absolute inset-x-0 top-0 z-20 bg-gradient-to-b from-black/70 to-transparent p-3 pb-8">
        <div className="flex gap-1" aria-hidden="true">
          {group.statuses.map((item, position) => (
            <div key={item.id} className="h-1 flex-1 overflow-hidden rounded bg-white/30">
              <div
                className="h-full bg-white"
                data-testid="story-progress"
                style={{
                  width: `${position < state.index ? 100 : position > state.index ? 0 : Math.round(fraction * 100)}%`,
                }}
              />
            </div>
          ))}
        </div>
        <p className="sr-only" aria-live="polite">
          Status {state.index + 1} of {group.statuses.length}
        </p>
        <div className="mt-3 flex items-center gap-3">
          <Avatar name={group.owner.displayName} imageUrl={group.owner.profileImageUrl} size={36} />
          <div className="min-w-0 flex-1">
            <p className="truncate font-medium">{ownerName}</p>
            <p className="text-xs text-white/80">{formatStatusTime(status.createdAt)}</p>
          </div>
          {group.mine && (
            <>
              <button
                type="button"
                onClick={() => setViewersOpen(true)}
                aria-label="Viewers"
                title="Viewers"
                className="flex items-center gap-1 rounded-full px-2 py-1 hover:bg-white/20"
              >
                <Eye size={20} />
                <span className="text-sm" data-testid="status-view-count">
                  {viewCount}
                </span>
              </button>
              <button
                type="button"
                onClick={() => setConfirmDelete(true)}
                aria-label="Delete status"
                title="Delete status"
                className="rounded-full p-2 hover:bg-white/20"
              >
                <Trash2 size={20} />
              </button>
            </>
          )}
          <button
            type="button"
            onClick={() => setSpacePaused((value) => !value)}
            aria-label={spacePaused ? 'Resume' : 'Pause'}
            title={spacePaused ? 'Resume' : 'Pause'}
            className="rounded-full p-2 hover:bg-white/20"
          >
            {spacePaused ? <Play size={20} /> : <Pause size={20} />}
          </button>
          <button
            ref={closeRef}
            type="button"
            onClick={() => dispatch({ type: 'CLOSE' })}
            aria-label="Close"
            title="Close"
            className="rounded-full p-2 hover:bg-white/20"
          >
            <X size={22} />
          </button>
        </div>
      </div>

      {/* the status itself (a new element per status, so videos and photos start fresh) */}
      <div className="relative flex min-h-0 flex-1 items-center justify-center">
        <StatusContent
          key={status.id}
          status={status}
          paused={paused}
          onReady={() => dispatch({ type: 'READY', statusId: status.id })}
          onFailed={() => dispatch({ type: 'FAILED', statusId: status.id })}
          onVideoProgress={(value) => dispatch({ type: 'VIDEO_PROGRESS', statusId: status.id, fraction: value })}
          onVideoEnded={() => dispatch({ type: 'NEXT' })}
        />

        {/* Tap areas: left half = previous, right half = next. Holding either one pauses. */}
        <button
          type="button"
          aria-label="Previous"
          onPointerDown={pressStart}
          onPointerUp={pressEnd}
          onPointerLeave={pressEnd}
          onPointerCancel={pressEnd}
          onClick={() => tap('PREVIOUS')}
          className="group absolute inset-y-0 left-0 z-10 flex w-1/2 items-center justify-start pl-2 outline-none"
        >
          <ChevronLeft size={32} className="rounded-full bg-black/30 opacity-0 transition group-hover:opacity-100 group-focus-visible:opacity-100" />
        </button>
        <button
          type="button"
          aria-label="Next"
          onPointerDown={pressStart}
          onPointerUp={pressEnd}
          onPointerLeave={pressEnd}
          onPointerCancel={pressEnd}
          onClick={() => tap('NEXT')}
          className="group absolute inset-y-0 right-0 z-10 flex w-1/2 items-center justify-end pr-2 outline-none"
        >
          <ChevronRight size={32} className="rounded-full bg-black/30 opacity-0 transition group-hover:opacity-100 group-focus-visible:opacity-100" />
        </button>

        {status.caption && (
          <p className="pointer-events-none absolute inset-x-0 bottom-0 z-20 bg-gradient-to-t from-black/80 to-transparent px-4 pb-6 pt-10 text-center whitespace-pre-wrap break-words">
            {status.caption}
          </p>
        )}
        {error && (
          <p role="alert" className="absolute inset-x-4 top-28 z-20 rounded bg-red-900/80 px-3 py-2 text-sm">
            {error}
          </p>
        )}
        {viewersOpen && <StatusViewersDialog statusId={status.id} onClose={() => setViewersOpen(false)} />}
      </div>

      {confirmDelete && (
        <ConfirmDialog
          title="Delete this status?"
          message="It will be deleted for everybody who can see it."
          confirmLabel="Delete"
          busy={deleting}
          onConfirm={() => void confirmDeletion()}
          onCancel={() => setConfirmDelete(false)}
        />
      )}
    </div>
  )
}

// What one status shows: the text on its colour, the photo, or the video.
function StatusContent({
  status,
  paused,
  onReady,
  onFailed,
  onVideoProgress,
  onVideoEnded,
}: {
  status: Status
  paused: boolean
  onReady: () => void
  onFailed: () => void
  onVideoProgress: (fraction: number) => void
  onVideoEnded: () => void
}) {
  if (status.type === 'TEXT') {
    return (
      <div
        className="flex h-full w-full items-center justify-center p-8"
        style={{ backgroundColor: status.backgroundColor ?? '#334155' }}
        data-testid="story-text"
      >
        <p className="max-w-xl text-center text-2xl font-medium whitespace-pre-wrap break-words sm:text-3xl">{status.text}</p>
      </div>
    )
  }
  if (status.type === 'IMAGE') {
    return <StatusPhoto status={status} onReady={onReady} onFailed={onFailed} />
  }
  return (
    <StatusVideo
      status={status}
      paused={paused}
      onReady={onReady}
      onFailed={onFailed}
      onProgress={onVideoProgress}
      onEnded={onVideoEnded}
    />
  )
}

function StatusPhoto({ status, onReady, onFailed }: { status: Status; onReady: () => void; onFailed: () => void }) {
  const thumbnail = useStatusMediaUrl(status.id, 'thumbnail', status.media?.hasThumbnail ?? false)
  const original = useStatusMediaUrl(status.id, 'original')
  const [loaded, setLoaded] = useState(false)
  const failed = original.failed

  useEffect(() => {
    if (failed) onFailed()
  }, [failed, onFailed])

  return (
    <div className="relative flex h-full w-full items-center justify-center">
      {/* the small preview first (blurred), so there is something to see while the photo loads */}
      {thumbnail.url && !loaded && (
        <img src={thumbnail.url} alt="" aria-hidden="true" className="absolute max-h-full max-w-full object-contain blur-sm" />
      )}
      {original.url && (
        <img
          src={original.url}
          alt={status.caption || 'Status photo'}
          data-testid="story-photo"
          className="relative max-h-full max-w-full object-contain"
          onLoad={() => {
            setLoaded(true)
            onReady()
          }}
          onError={() => {
            original.reload() // an expired link: ask for a new one once
            onFailed()
          }}
        />
      )}
      {failed && <p className="text-sm text-white/80">This photo could not be loaded.</p>}
    </div>
  )
}

function StatusVideo({
  status,
  paused,
  onReady,
  onFailed,
  onProgress,
  onEnded,
}: {
  status: Status
  paused: boolean
  onReady: () => void
  onFailed: () => void
  onProgress: (fraction: number) => void
  onEnded: () => void
}) {
  const source = useStatusMediaUrl(status.id, 'original')
  const videoRef = useRef<HTMLVideoElement>(null)
  const failed = source.failed

  useEffect(() => {
    if (failed) onFailed()
  }, [failed, onFailed])

  // Pause / play together with the viewer. If the browser refuses to play with sound, play muted.
  useEffect(() => {
    const video = videoRef.current
    if (!video || !source.url) return
    if (paused) {
      video.pause?.()
      return
    }
    const attempt = video.play?.()
    if (attempt && typeof attempt.catch === 'function') {
      attempt.catch(() => {
        video.muted = true
        const muted = video.play?.()
        if (muted && typeof muted.catch === 'function') muted.catch(() => undefined)
      })
    }
  }, [paused, source.url])

  if (failed) return <p className="text-sm text-white/80">This video could not be loaded.</p>
  if (!source.url) return <p className="text-sm text-white/80">Loading...</p>
  return (
    <video
      ref={videoRef}
      src={source.url}
      playsInline
      aria-label={status.caption || 'Status video'}
      data-testid="story-video"
      className="max-h-full max-w-full"
      onLoadedData={onReady}
      onTimeUpdate={(event) => {
        const { currentTime, duration } = event.currentTarget
        if (Number.isFinite(duration) && duration > 0) onProgress(currentTime / duration)
      }}
      onEnded={onEnded}
      onError={() => {
        source.reload()
        onFailed()
      }}
    />
  )
}
