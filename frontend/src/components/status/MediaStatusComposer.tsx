import { Loader2, Send, X } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { describeStatusError, MAX_STATUS_CAPTION } from '../../features/status/statusRules'
import { StatusSender } from '../../features/status/statusSender'
import type { Status } from '../../types/status'

// Full-screen preview of a picked photo / video with a caption box. Post uploads the file (progress bar, Cancel)
// and then posts the status. After an error Post tries again with the same draft: the upload is skipped when it
// already worked, and the same idempotency key is sent (see StatusSender).
// The file was already checked by the page (type and size, mediaRules.validateFile).
export default function MediaStatusComposer({
  file,
  kind,
  onPosted,
  onClose,
}: {
  file: File
  kind: 'IMAGE' | 'VIDEO'
  onPosted: (status: Status) => void
  onClose: () => void
}) {
  const [caption, setCaption] = useState('')
  const [posting, setPosting] = useState(false)
  const [progress, setProgress] = useState<number | null>(null) // null = not uploading
  const [error, setError] = useState<string | null>(null)
  const [sender] = useState(() => new StatusSender())
  // The duration the browser measured for a video (sent with the upload, like video messages).
  const durationRef = useRef<number | null>(null)

  // A local link (blob:) so the user sees the file before it is uploaded. Freed when the composer closes.
  const [previewUrl] = useState(() => URL.createObjectURL(file))
  useEffect(() => () => URL.revokeObjectURL(previewUrl), [previewUrl])

  // Closing also stops an upload that is still running.
  function close() {
    sender.cancel()
    onClose()
  }

  // Escape closes too.
  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        sender.cancel()
        onClose()
      }
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [sender, onClose])

  async function post() {
    if (posting) return
    setPosting(true)
    setError(null)
    try {
      const status = await sender.postMedia(
        { file, fileName: file.name, kind, caption, durationSeconds: durationRef.current },
        (percent) => setProgress(percent),
      )
      onPosted(status)
    } catch (caught) {
      setError(sender.wasCancelled() ? 'Upload cancelled. Press Post to try again.' : describeStatusError(caught))
      setProgress(null)
      setPosting(false)
    }
  }

  // The bar shows while the file is being sent (a retry whose file is already uploaded shows no bar).
  const uploading = posting && progress !== null

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={kind === 'IMAGE' ? 'New photo status' : 'New video status'}
      className="fixed inset-0 z-50 flex flex-col bg-black text-white"
      data-testid="media-status-composer"
    >
      <div className="flex items-center justify-between p-3">
        <button type="button" onClick={close} aria-label="Close" className="rounded-full p-2 hover:bg-white/20">
          <X size={24} />
        </button>
        <span className="truncate text-sm text-white/80">{file.name}</span>
      </div>

      <div className="flex min-h-0 flex-1 items-center justify-center p-2">
        {kind === 'IMAGE' ? (
          <img src={previewUrl} alt="Preview of the photo" className="max-h-full max-w-full object-contain" />
        ) : (
          <video
            src={previewUrl}
            controls
            playsInline
            aria-label="Preview of the video"
            className="max-h-full max-w-full"
            onLoadedMetadata={(event) => {
              const seconds = event.currentTarget.duration
              durationRef.current = Number.isFinite(seconds) ? Math.round(seconds) : null
            }}
          />
        )}
      </div>

      {uploading && (
        <div className="px-4">
          <div
            role="progressbar"
            aria-label="Upload progress"
            aria-valuemin={0}
            aria-valuemax={100}
            aria-valuenow={progress ?? 0}
            className="h-1.5 w-full overflow-hidden rounded bg-white/20"
          >
            <div className="h-full bg-brand-500 transition-all" style={{ width: `${progress ?? 0}%` }} />
          </div>
          <div className="mt-1 flex items-center justify-between text-xs text-white/80">
            <span>Uploading... {progress}%</span>
            <button type="button" onClick={() => sender.cancel()} className="rounded px-2 py-1 hover:bg-white/20">
              Cancel
            </button>
          </div>
        </div>
      )}

      {error && (
        <p role="alert" className="mx-4 mt-2 rounded bg-red-900/70 px-3 py-2 text-sm">
          {error}
        </p>
      )}

      <div className="flex items-end gap-2 p-4">
        <div className="flex-1">
          <input
            type="text"
            value={caption}
            onChange={(event) => setCaption(event.target.value)}
            maxLength={MAX_STATUS_CAPTION}
            disabled={posting}
            placeholder="Add a caption"
            aria-label="Caption"
            className="w-full rounded-full bg-white/10 px-4 py-2 text-white placeholder-white/60 outline-none focus:bg-white/20"
          />
          {caption.length > MAX_STATUS_CAPTION - 100 && (
            <span className="mt-1 block text-right text-xs text-white/70" aria-live="polite">
              {caption.length} / {MAX_STATUS_CAPTION}
            </span>
          )}
        </div>
        <button
          type="button"
          onClick={() => void post()}
          disabled={posting}
          aria-label="Post"
          className="flex items-center gap-2 rounded-full bg-brand-600 px-4 py-2 font-medium text-white hover:bg-brand-700 disabled:cursor-not-allowed disabled:opacity-60"
        >
          {posting ? <Loader2 className="animate-spin" size={18} /> : <Send size={18} />}
          Post
        </button>
      </div>
    </div>
  )
}
