import { FileText, Image as ImageIcon, Music, Paperclip, Video } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import type { MediaKind } from '../../types/chat'

type PickableKind = Exclude<MediaKind, 'VOICE'> // voice notes are recorded, not picked

const ITEMS: { kind: PickableKind; label: string; icon: typeof ImageIcon }[] = [
  { kind: 'IMAGE', label: 'Photo', icon: ImageIcon },
  { kind: 'VIDEO', label: 'Video', icon: Video },
  { kind: 'DOCUMENT', label: 'Document', icon: FileText },
  { kind: 'AUDIO', label: 'Audio', icon: Music },
]

// The paperclip button: opens a small menu above it (Photo, Video, Document, Audio).
// Closes on a click outside, on Escape and after a choice.
export default function AttachMenu({ onPick, disabled }: { onPick: (kind: PickableKind) => void; disabled?: boolean }) {
  const [open, setOpen] = useState(false)
  const rootRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    function onPointerDown(event: MouseEvent) {
      if (rootRef.current && !rootRef.current.contains(event.target as Node)) setOpen(false)
    }
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') setOpen(false)
    }
    document.addEventListener('mousedown', onPointerDown)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('mousedown', onPointerDown)
      document.removeEventListener('keydown', onKeyDown)
    }
  }, [open])

  return (
    <div ref={rootRef} className="relative">
      <button
        type="button"
        aria-label="Attach a file"
        aria-haspopup="menu"
        aria-expanded={open}
        disabled={disabled}
        onClick={() => setOpen((value) => !value)}
        className="rounded p-2 text-gray-500 hover:bg-gray-100 disabled:cursor-not-allowed disabled:opacity-50 dark:text-gray-300 dark:hover:bg-gray-700"
      >
        <Paperclip size={20} />
      </button>
      {open && (
        <div
          role="menu"
          aria-label="Attach a file"
          className="absolute bottom-full left-0 z-20 mb-2 w-44 animate-pop rounded-xl border border-gray-200 bg-white p-1 text-sm shadow-float dark:border-gray-700 dark:bg-gray-900"
        >
          {ITEMS.map(({ kind, label, icon: Icon }) => (
            <button
              key={kind}
              type="button"
              role="menuitem"
              onClick={() => {
                setOpen(false)
                onPick(kind)
              }}
              className="flex w-full items-center gap-2 px-3 py-2 text-left hover:bg-gray-100 dark:hover:bg-gray-800"
            >
              <Icon size={16} />
              {label}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
