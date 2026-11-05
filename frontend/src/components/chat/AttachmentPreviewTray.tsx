import { FileText, Music, Video, X } from 'lucide-react'
import { humanSize } from '../../features/media/mediaRules'
import type { MediaKind } from '../../types/chat'

const ICONS = { IMAGE: FileText, VIDEO: Video, AUDIO: Music, VOICE: Music, DOCUMENT: FileText }

// Shown above the text box after a file was picked: a preview and a remove button.
// The text box below is the caption of the file.
export default function AttachmentPreviewTray({
  file,
  kind,
  imageUrl,
  onRemove,
}: {
  file: File
  kind: MediaKind
  imageUrl: string | null // only for pictures
  onRemove: () => void
}) {
  const Icon = ICONS[kind]
  return (
    <div className="mb-2 flex items-center gap-3 rounded-lg border border-gray-200 bg-gray-50 p-2 dark:border-gray-600 dark:bg-gray-700" data-testid="attachment-preview">
      {imageUrl ? (
        <img src={imageUrl} alt={`Preview of ${file.name}`} className="h-16 w-16 rounded object-cover" />
      ) : (
        <span className="flex h-16 w-16 items-center justify-center rounded bg-gray-200 text-gray-500 dark:bg-gray-600 dark:text-gray-200">
          <Icon size={28} aria-hidden="true" />
        </span>
      )}
      <div className="min-w-0 flex-1 text-sm">
        <p className="truncate font-medium">{file.name}</p>
        <p className="text-xs text-gray-500 dark:text-gray-300">{humanSize(file.size)}. Add a caption below if you like.</p>
      </div>
      <button
        type="button"
        onClick={onRemove}
        aria-label="Remove attachment"
        className="rounded p-1 text-gray-500 hover:bg-gray-200 dark:text-gray-300 dark:hover:bg-gray-600"
      >
        <X size={18} />
      </button>
    </div>
  )
}
