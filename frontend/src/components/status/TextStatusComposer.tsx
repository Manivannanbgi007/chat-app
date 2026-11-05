import { Loader2, Palette, Send, X } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { describeStatusError, MAX_STATUS_TEXT, nextColor, STATUS_COLORS } from '../../features/status/statusRules'
import { StatusSender } from '../../features/status/statusSender'
import type { Status } from '../../types/status'

// Full-screen editor for a text status: the text sits in the middle of the chosen background colour.
// The colour button walks through the 8 colours the server accepts. Post is safe to press again after an error:
// the same draft always uses the same idempotency key (see StatusSender).
export default function TextStatusComposer({
  onPosted,
  onClose,
}: {
  onPosted: (status: Status) => void
  onClose: () => void
}) {
  const [text, setText] = useState('')
  const [color, setColor] = useState<string>(STATUS_COLORS[0])
  const [posting, setPosting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [sender] = useState(() => new StatusSender())
  const textRef = useRef<HTMLTextAreaElement>(null)

  useEffect(() => {
    textRef.current?.focus()
  }, [])

  // Escape closes the editor (but not while the status is being sent).
  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape' && !posting) onClose()
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [onClose, posting])

  const canPost = text.trim().length > 0 && !posting

  async function post() {
    if (!canPost) return
    setPosting(true)
    setError(null)
    try {
      const status = await sender.postText(text, color)
      onPosted(status)
    } catch (caught) {
      setError(describeStatusError(caught))
      setPosting(false)
    }
  }

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="New text status"
      className="fixed inset-0 z-50 flex flex-col text-white"
      style={{ backgroundColor: color }}
      data-testid="text-status-composer"
      data-color={color}
    >
      <div className="flex items-center justify-between p-3">
        <button
          type="button"
          onClick={onClose}
          disabled={posting}
          aria-label="Close"
          className="rounded-full p-2 hover:bg-white/20 disabled:opacity-50"
        >
          <X size={24} />
        </button>
        <button
          type="button"
          onClick={() => setColor((current) => nextColor(current))}
          aria-label="Change background colour"
          title="Change background colour"
          className="rounded-full p-2 hover:bg-white/20"
        >
          <Palette size={24} />
        </button>
      </div>

      <div className="flex flex-1 items-center justify-center px-6">
        <textarea
          ref={textRef}
          value={text}
          onChange={(event) => setText(event.target.value)}
          maxLength={MAX_STATUS_TEXT}
          rows={6}
          placeholder="Type a status"
          aria-label="Status text"
          className="w-full max-w-xl resize-none bg-transparent text-center text-2xl font-medium text-white placeholder-white/70 outline-none sm:text-3xl"
        />
      </div>

      <div className="flex items-center justify-between gap-3 p-4">
        <span className="text-sm text-white/80" aria-live="polite">
          {text.length} / {MAX_STATUS_TEXT}
        </span>
        {error && (
          <p role="alert" className="flex-1 rounded bg-black/30 px-3 py-2 text-sm">
            {error}
          </p>
        )}
        <button
          type="button"
          onClick={() => void post()}
          disabled={!canPost}
          aria-label="Post"
          className="flex items-center gap-2 rounded-full bg-white px-4 py-2 font-medium text-gray-900 hover:bg-gray-100 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {posting ? <Loader2 className="animate-spin" size={18} /> : <Send size={18} />}
          Post
        </button>
      </div>
    </div>
  )
}
