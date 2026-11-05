import { useQuery } from '@tanstack/react-query'
import { Loader2, X } from 'lucide-react'
import { useId } from 'react'
import Alert from '../common/Alert'
import Avatar from '../common/Avatar'
import { currentName } from '../../features/messaging/conversationInfo'
import { formatDateTime } from '../../features/messaging/conversationList'
import { toAppError } from '../../services/api/appError'
import { getMessageReceipts } from '../../services/api/chatApi'
import type { ChatMessage, Conversation, MessageReceiptInfo } from '../../types/chat'

// "Message info" for a message I sent: who has read it, who has only received it, who is still waiting.
// The list comes from GET /api/messages/{id}/receipts (only the sender may ask).
export default function MessageInfoDialog({
  message,
  conversation,
  onClose,
}: {
  message: ChatMessage
  conversation: Conversation | undefined
  onClose: () => void
}) {
  const titleId = useId()
  const receiptsQuery = useQuery({
    queryKey: ['message-receipts', message.id],
    queryFn: () => getMessageReceipts(message.id),
  })

  const receipts = receiptsQuery.data ?? []
  const readBy = receipts.filter((receipt) => receipt.status === 'READ')
  const deliveredTo = receipts.filter((receipt) => receipt.status === 'DELIVERED')
  const waiting = receipts.filter((receipt) => receipt.status === 'SENT')

  return (
    <div className="fixed inset-0 z-30 flex items-center justify-center bg-gray-950/55 backdrop-blur-sm animate-fade-in sm:p-4">
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        className="flex h-full w-full flex-col bg-white p-4 shadow-lg sm:h-auto sm:max-h-[90vh] sm:max-w-sm sm:animate-pop sm:rounded-2xl dark:bg-gray-900"
      >
        <div className="mb-3 flex items-center">
          <h2 id={titleId} className="flex-1 text-lg font-semibold">
            Message info
          </h2>
          <button type="button" onClick={onClose} aria-label="Close" className="rounded p-1 hover:bg-gray-100 dark:hover:bg-gray-800">
            <X size={20} />
          </button>
        </div>
        <p className="mb-3 line-clamp-3 rounded bg-brand-50 px-3 py-2 text-sm dark:bg-brand-900">{message.content}</p>

        {receiptsQuery.isPending && (
          <p className="flex items-center gap-2 text-sm">
            <Loader2 className="animate-spin" size={16} /> Loading...
          </p>
        )}
        {receiptsQuery.error && <Alert type="error">{toAppError(receiptsQuery.error).message}</Alert>}

        {receiptsQuery.data && (
          <div className="min-h-0 flex-1 space-y-4 overflow-y-auto">
            <ReceiptGroup title="Read by" receipts={readBy} conversation={conversation} time={(r) => r.readAt} />
            <ReceiptGroup title="Delivered to" receipts={deliveredTo} conversation={conversation} time={(r) => r.deliveredAt} />
            <ReceiptGroup title="Waiting" receipts={waiting} conversation={conversation} time={() => null} />
          </div>
        )}
      </div>
    </div>
  )
}

function ReceiptGroup({
  title,
  receipts,
  conversation,
  time,
}: {
  title: string
  receipts: MessageReceiptInfo[]
  conversation: Conversation | undefined
  time: (receipt: MessageReceiptInfo) => string | null
}) {
  return (
    <section aria-label={title}>
      <h3 className="mb-1 text-sm font-semibold text-gray-600 dark:text-gray-300">{title}</h3>
      {receipts.length === 0 ? (
        <p className="text-sm text-gray-400">Nobody</p>
      ) : (
        <ul>
          {receipts.map((receipt) => {
            const name = currentName(conversation, receipt.userId) ?? 'Former member'
            const at = time(receipt)
            return (
              <li key={receipt.userId} className="flex items-center gap-3 py-1">
                <Avatar name={name} size={28} />
                <span className="flex-1 truncate text-sm">{name}</span>
                {at && (
                  <time dateTime={at} className="text-xs text-gray-500">
                    {formatDateTime(at)}
                  </time>
                )}
              </li>
            )
          })}
        </ul>
      )}
    </section>
  )
}
