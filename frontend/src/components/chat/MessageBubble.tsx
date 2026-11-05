import { Info } from 'lucide-react'
import { displayStatus } from '../../features/messaging/messageMerge'
import { formatClock } from '../../features/messaging/conversationList'
import type { ChatMessage } from '../../types/chat'
import MessageAttachment from './MessageAttachment'
import StatusTicks from './StatusTicks'

// One message: green on the right when it is mine, white on the left when it is from someone else.
// In groups, other people's messages can show the sender's name on top, and my messages get an
// "Info" button that shows who received and read them.
// A media message shows its file (picture, video, player, document) above the caption. While my file is
// uploading there is a progress bar and a Cancel button.
export default function MessageBubble({
  message,
  mine,
  onRetry,
  onCancel,
  sender,
  onShowInfo,
}: {
  message: ChatMessage
  mine: boolean
  onRetry: (message: ChatMessage) => void
  // Cancel an upload in progress / discard a failed media message (only media messages have it)
  onCancel?: (message: ChatMessage) => void
  // name + colour class of the sender (only given for group messages that start a new "block" of a sender)
  sender?: { name: string; colourClass: string } | null
  onShowInfo?: (message: ChatMessage) => void
}) {
  const status = mine ? displayStatus(message) : null
  const failed = message.localStatus === 'FAILED'
  const progress = message.uploadProgress
  const uploading = message.localStatus === 'SENDING' && progress !== undefined && progress < 100
  const hasAttachment = !!message.attachment
  // Text messages always show their text; media messages only show a caption when there is one.
  const showText = !hasAttachment || message.content.trim().length > 0

  return (
    <div className={`flex animate-bubble-in ${mine ? 'justify-end' : 'justify-start'}`} data-testid="message">
      <div
        className={`max-w-[85%] rounded-2xl px-3.5 py-2 shadow-sm sm:max-w-[75%] xl:max-w-[60%] ${
          mine
            ? 'rounded-br-md bg-gradient-to-br from-brand-500 to-brand-700 text-white [&_[aria-label=Read]]:text-sky-200'
            : 'rounded-bl-md bg-white text-gray-900 dark:bg-gray-800 dark:text-gray-100'
        }`}
      >
        {sender && <p className={`mb-0.5 text-xs font-semibold ${sender.colourClass}`}>{sender.name}</p>}
        {hasAttachment && <MessageAttachment message={message} />}
        {uploading && (
          <div className="mt-1">
            <div
              role="progressbar"
              aria-label="Upload progress"
              aria-valuemin={0}
              aria-valuemax={100}
              aria-valuenow={progress}
              className="h-1.5 w-full overflow-hidden rounded bg-white/30"
            >
              <div className="h-full bg-white transition-all" style={{ width: `${progress}%` }} />
            </div>
            <div className="mt-1 flex items-center justify-between text-xs opacity-90">
              <span>Uploading {progress}%</span>
              {onCancel && (
                <button
                  type="button"
                  onClick={() => onCancel(message)}
                  aria-label="Cancel upload"
                  className="rounded-lg border border-current/40 px-2 py-0.5 font-medium hover:bg-black/10"
                >
                  Cancel
                </button>
              )}
            </div>
          </div>
        )}
        {showText && <p className={`whitespace-pre-wrap break-words ${hasAttachment ? 'mt-1' : ''}`}>{message.content}</p>}
        <div className={`mt-0.5 flex items-center justify-end gap-1 text-[11px] ${mine ? 'text-white/75' : 'text-gray-400 dark:text-gray-400'}`}>
          {onShowInfo && mine && !message.localStatus && (
            <button
              type="button"
              onClick={() => onShowInfo(message)}
              aria-label="Message info"
              title="Message info"
              className="rounded p-0.5 hover:bg-black/15"
            >
              <Info size={12} />
            </button>
          )}
          <time dateTime={message.createdAt}>{formatClock(message.createdAt)}</time>
          {status && <StatusTicks status={status} />}
        </div>
        {failed && (
          <div className="mt-1 flex items-center justify-end gap-2 text-xs text-red-600 dark:text-red-400">
            <span>{message.errorMessage ?? 'The message could not be sent.'}</span>
            <button
              type="button"
              onClick={() => onRetry(message)}
              className="rounded border border-red-300 px-2 py-0.5 font-medium hover:bg-red-50 dark:border-red-700 dark:hover:bg-red-900"
            >
              Retry
            </button>
            {onCancel && hasAttachment && (
              <button
                type="button"
                onClick={() => onCancel(message)}
                aria-label="Discard message"
                className="rounded border border-gray-300 px-2 py-0.5 font-medium text-gray-700 hover:bg-gray-100 dark:border-gray-500 dark:text-gray-200 dark:hover:bg-gray-600"
              >
                Discard
              </button>
            )}
          </div>
        )}
      </div>
    </div>
  )
}
