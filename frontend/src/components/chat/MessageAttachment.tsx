import { Download, FileText, Mic, Music, RotateCw } from 'lucide-react'
import { useState } from 'react'
import { formatDuration, humanSize } from '../../features/media/mediaRules'
import { getLocalPreview } from '../../features/media/localPreviews'
import { useAttachmentUrl } from '../../features/media/useAttachmentUrl'
import { getAttachmentUrl } from '../../services/api/chatApi'
import type { Attachment, ChatMessage } from '../../types/chat'
import ImageLightbox from './ImageLightbox'

// The file of a media message, drawn by kind: picture, video, audio / voice note or document card.
// A message that is still being sent shows the file from this computer (previewUrl). A saved message asks for a
// signed link when it is drawn (useAttachmentUrl). The signed link is only kept in memory.
export default function MessageAttachment({ message }: { message: ChatMessage }) {
  const attachment = message.attachment
  if (!attachment) return null
  const notSavedYet = message.localStatus !== undefined || message.id.startsWith('local-')
  const localUrl = message.previewUrl ?? getLocalPreview(message.id) ?? null

  switch (attachment.kind) {
    case 'IMAGE':
      return <ImageAttachment message={message} attachment={attachment} localUrl={localUrl} notSavedYet={notSavedYet} />
    case 'VIDEO':
      return <VideoAttachment message={message} attachment={attachment} localUrl={localUrl} notSavedYet={notSavedYet} />
    case 'AUDIO':
    case 'VOICE':
      return <AudioAttachment message={message} attachment={attachment} localUrl={localUrl} notSavedYet={notSavedYet} />
    case 'DOCUMENT':
      return <DocumentAttachment message={message} attachment={attachment} notSavedYet={notSavedYet} />
  }
}

interface Props {
  message: ChatMessage
  attachment: Attachment
  localUrl: string | null
  notSavedYet: boolean
}

// The grey box shown while the signed link is being fetched, or when fetching failed.
function Placeholder({ failed, onRetry, label }: { failed: boolean; onRetry: () => void; label: string }) {
  if (!failed) {
    return <div className="h-full w-full animate-pulse bg-gray-200 dark:bg-gray-600" role="status" aria-label={`Loading ${label}`} />
  }
  return (
    <div className="flex h-full w-full flex-col items-center justify-center gap-1 bg-gray-100 p-2 text-center text-xs text-gray-600 dark:bg-gray-600 dark:text-gray-200">
      <span>Could not load {label}.</span>
      <button type="button" onClick={onRetry} className="flex items-center gap-1 rounded border border-gray-400 px-2 py-0.5 hover:bg-white/50">
        <RotateCw size={12} /> Retry
      </button>
    </div>
  )
}

function ImageAttachment({ message, attachment, localUrl, notSavedYet }: Props) {
  const [open, setOpen] = useState(false)
  // The small preview when there is one (it loads faster), else the original.
  const variant = attachment.hasThumbnail ? 'thumbnail' : 'original'
  const thumb = useAttachmentUrl(message.id, variant, !localUrl && !notSavedYet)
  const src = localUrl ?? thumb.url
  // Same shape as the picture, so the chat does not jump when the picture appears.
  const ratio = attachment.width && attachment.height ? `${attachment.width} / ${attachment.height}` : '4 / 3'

  return (
    <>
      <div className="w-full max-w-[280px] overflow-hidden rounded" style={{ aspectRatio: ratio }}>
        {src ? (
          <button
            type="button"
            onClick={() => setOpen(true)}
            aria-label={`Open photo ${attachment.fileName}`}
            className="block h-full w-full"
          >
            <img src={src} alt={attachment.fileName} className="h-full w-full object-cover" onError={thumb.reload} />
          </button>
        ) : (
          <Placeholder failed={thumb.failed} onRetry={() => void thumb.refetch()} label="photo" />
        )}
      </div>
      {open && <FullImage message={message} attachment={attachment} localUrl={localUrl} notSavedYet={notSavedYet} onClose={() => setOpen(false)} />}
    </>
  )
}

// The original picture in the lightbox (only fetched when it is opened).
function FullImage({ message, attachment, localUrl, notSavedYet, onClose }: Props & { onClose: () => void }) {
  const original = useAttachmentUrl(message.id, 'original', !localUrl && !notSavedYet)
  return <ImageLightbox src={localUrl ?? original.url} alt={attachment.fileName} onClose={onClose} />
}

function VideoAttachment({ message, attachment, localUrl, notSavedYet }: Props) {
  const source = useAttachmentUrl(message.id, 'original', !localUrl && !notSavedYet)
  const src = localUrl ?? source.url
  return (
    <div className="w-full max-w-[320px] overflow-hidden rounded bg-black" style={{ aspectRatio: '16 / 9' }}>
      {src ? (
        <video
          src={src}
          controls
          preload="metadata"
          aria-label={`Video ${attachment.fileName}`}
          className="h-full w-full"
          onError={source.reload}
        />
      ) : (
        <Placeholder failed={source.failed} onRetry={() => void source.refetch()} label="video" />
      )}
    </div>
  )
}

function AudioAttachment({ message, attachment, localUrl, notSavedYet }: Props) {
  const source = useAttachmentUrl(message.id, 'original', !localUrl && !notSavedYet)
  const src = localUrl ?? source.url
  const voice = attachment.kind === 'VOICE'
  const title = voice ? 'Voice message' : attachment.fileName
  const Icon = voice ? Mic : Music

  return (
    <div className="w-64 max-w-full">
      <div className="mb-1 flex items-center gap-2 text-sm">
        <Icon size={16} aria-hidden="true" className="shrink-0 text-gray-500 dark:text-gray-300" />
        <span className="min-w-0 flex-1 truncate">{title}</span>
        {attachment.durationSeconds !== null && attachment.durationSeconds !== undefined && (
          <span className="text-xs tabular-nums text-gray-500 dark:text-gray-300">{formatDuration(attachment.durationSeconds)}</span>
        )}
      </div>
      {src ? (
        <audio src={src} controls preload="metadata" aria-label={title} className="h-10 w-full" onError={source.reload} />
      ) : (
        <div className="h-10 overflow-hidden rounded">
          <Placeholder failed={source.failed} onRetry={() => void source.refetch()} label={voice ? 'voice message' : 'audio'} />
        </div>
      )}
    </div>
  )
}

function DocumentAttachment({ message, attachment, notSavedYet }: Omit<Props, 'localUrl'>) {
  const [busy, setBusy] = useState(false)
  const [failed, setFailed] = useState(false)

  // A link is asked for at the moment of the click, so it is always fresh. The storage sends the file
  // with "Content-Disposition: attachment", so the browser downloads it instead of showing it.
  async function download() {
    setBusy(true)
    setFailed(false)
    try {
      const { url } = await getAttachmentUrl(message.id, 'original')
      const link = document.createElement('a')
      link.href = url
      link.download = attachment.fileName
      link.rel = 'noopener'
      document.body.appendChild(link)
      link.click()
      link.remove()
    } catch {
      setFailed(true)
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex w-64 max-w-full items-center gap-3 rounded bg-black/5 p-2 dark:bg-white/10">
      <FileText size={32} aria-hidden="true" className="shrink-0 text-gray-500 dark:text-gray-300" />
      <div className="min-w-0 flex-1 text-sm">
        <p className="truncate font-medium" title={attachment.fileName}>
          {attachment.fileName}
        </p>
        <p className="text-xs text-gray-500 dark:text-gray-300">{humanSize(attachment.sizeBytes)}</p>
        {failed && <p className="text-xs text-red-600 dark:text-red-400">The download could not start. Try again.</p>}
      </div>
      <button
        type="button"
        onClick={() => void download()}
        disabled={busy || notSavedYet}
        aria-label={`Download ${attachment.fileName}`}
        className="rounded-full p-2 text-gray-600 hover:bg-black/10 disabled:opacity-40 dark:text-gray-200 dark:hover:bg-white/20"
      >
        <Download size={18} />
      </button>
    </div>
  )
}
