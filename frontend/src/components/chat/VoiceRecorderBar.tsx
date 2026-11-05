import { Send, Trash2 } from 'lucide-react'
import { MAX_VOICE_SECONDS } from '../../features/media/useVoiceRecorder'
import { formatDuration } from '../../features/media/mediaRules'

// Replaces the text box while a voice note is being recorded: a pulsing dot, the time, Cancel and Send.
export default function VoiceRecorderBar({
  seconds,
  onCancel,
  onSend,
}: {
  seconds: number
  onCancel: () => void
  onSend: () => void
}) {
  return (
    <div className="flex flex-1 items-center gap-3 rounded-lg bg-gray-100 px-3 py-2 dark:bg-gray-700" role="group" aria-label="Recording a voice note">
      <button
        type="button"
        onClick={onCancel}
        aria-label="Cancel recording"
        className="rounded p-1 text-gray-600 hover:bg-gray-200 dark:text-gray-200 dark:hover:bg-gray-600"
      >
        <Trash2 size={20} />
      </button>
      <span className="h-3 w-3 animate-pulse rounded-full bg-red-500" aria-hidden="true" />
      <span className="flex-1 text-sm tabular-nums" aria-live="off">
        Recording {formatDuration(seconds)} <span className="text-gray-500 dark:text-gray-300">/ {formatDuration(MAX_VOICE_SECONDS)}</span>
      </span>
      <button
        type="button"
        onClick={onSend}
        aria-label="Send voice note"
        className="rounded-full bg-brand-600 p-2 text-white hover:bg-brand-700"
      >
        <Send size={20} />
      </button>
    </div>
  )
}
