// Sending a message that carries a file: show it at once, upload the file (with progress), then send the
// message. The sibling of sendMessage() in features/messaging/chatActions.ts.
//
// Two steps, each can fail on its own:
//   1. POST /api/media/upload   -> mediaId
//   2. POST /api/messages       -> the saved message (with the SAME idempotencyKey on every try)
// Retry skips step 1 when the file is already uploaded, so the file is never uploaded twice.
import { describeSendError, refreshConversation } from '../messaging/chatActions'
import { toAppError } from '../../services/api/appError'
import * as chatApi from '../../services/api/chatApi'
import { uploadMedia } from '../../services/api/mediaApi'
import { useAuthStore } from '../../store/authStore'
import { useChatStore } from '../../store/chatStore'
import type { Attachment, ChatMessage, MediaKind } from '../../types/chat'
import { rememberPreview } from './localPreviews'
import { hasUploadMessage, isRejection, messageForUploadError } from './mediaRules'

export interface AttachmentInput {
  file: Blob
  fileName: string
  kind: MediaKind
  caption: string
  // Measured by the browser for voice notes (and audio / video if known).
  durationSeconds: number | null
}

// Everything about an upload that is not plain data (the file, the abort switch) lives here, outside the store.
interface PendingUpload extends AttachmentInput {
  conversationId: string
  controller: AbortController | null
  mediaId: string | null // set once the upload worked
  previewUrl: string | null
  // Called when the server refuses the file for good (wrong type, too large...): the bubble is removed.
  onRejected?: (message: string) => void
}

const uploads = new Map<string, PendingUpload>()

// Does this message have a file that is still on its way (so Retry / Cancel go through this file)?
export function isUploadPending(idempotencyKey: string): boolean {
  return uploads.has(idempotencyKey)
}

function canPreview(kind: MediaKind): boolean {
  return kind === 'IMAGE' || kind === 'VIDEO' || kind === 'AUDIO' || kind === 'VOICE'
}

// Shows the message at once (SENDING, progress 0) and starts the upload.
export async function sendAttachment(
  conversationId: string,
  input: AttachmentInput,
  onRejected?: (message: string) => void,
): Promise<void> {
  const user = useAuthStore.getState().user
  if (!user) return

  const key = crypto.randomUUID()
  const previewUrl = canPreview(input.kind) ? URL.createObjectURL(input.file) : null
  const attachment: Attachment = {
    mediaId: '', // unknown until the upload is done
    kind: input.kind,
    fileName: input.fileName,
    mimeType: input.file.type,
    sizeBytes: input.file.size,
    width: null,
    height: null,
    durationSeconds: input.durationSeconds,
    hasThumbnail: false,
  }
  const pending: ChatMessage = {
    id: `local-${key}`,
    conversationId,
    senderId: user.id,
    type: input.kind,
    content: input.caption.trim(),
    createdAt: new Date().toISOString(),
    status: null,
    myReceipt: null,
    attachment,
    localStatus: 'SENDING',
    idempotencyKey: key,
    uploadProgress: 0,
    ...(previewUrl ? { previewUrl } : {}),
  }
  uploads.set(key, { ...input, conversationId, controller: null, mediaId: null, previewUrl, onRejected })
  useChatStore.getState().addPending(pending)
  await run(key)
}

// The "Retry" button of a failed media message: same key, and the upload is skipped if it already worked.
export async function retryAttachment(key: string): Promise<void> {
  await run(key)
}

// "Cancel" while uploading, or "Discard" on a failed message: abort the request and forget the message.
export function cancelAttachment(key: string): void {
  const entry = uploads.get(key)
  if (!entry) return
  entry.controller?.abort()
  useChatStore.getState().removePending(entry.conversationId, key)
  discard(key)
}

function discard(key: string): void {
  const entry = uploads.get(key)
  if (entry?.previewUrl) URL.revokeObjectURL(entry.previewUrl)
  uploads.delete(key)
}

async function run(key: string): Promise<void> {
  const entry = uploads.get(key)
  if (!entry) return
  const { conversationId } = entry
  useChatStore.getState().updatePending(conversationId, key, { localStatus: 'SENDING', errorMessage: undefined })

  try {
    if (!entry.mediaId) {
      entry.controller = new AbortController()
      useChatStore.getState().updatePending(conversationId, key, { uploadProgress: 0 })
      const media = await uploadMedia(entry.file, entry.fileName, entry.kind, entry.durationSeconds, {
        signal: entry.controller.signal,
        onProgress: (percent) => useChatStore.getState().updatePending(conversationId, key, { uploadProgress: percent }),
      })
      entry.mediaId = media.id
      // From now on the pending message knows its file id: the server copy can replace it if it arrives first.
      useChatStore.getState().updatePending(conversationId, key, {
        uploadProgress: 100,
        attachment: {
          mediaId: media.id,
          kind: media.kind,
          fileName: media.fileName,
          mimeType: media.mimeType,
          sizeBytes: media.sizeBytes,
          width: media.width,
          height: media.height,
          durationSeconds: media.durationSeconds,
          hasThumbnail: media.hasThumbnail,
        },
      })
    }

    const caption = useChatStore.getState().messages[conversationId]?.find((m) => m.idempotencyKey === key)?.content ?? ''
    const saved = await chatApi.sendMessage({
      conversationId,
      type: entry.kind,
      attachmentId: entry.mediaId,
      content: caption,
      idempotencyKey: key,
    })
    useChatStore.getState().resolvePending(conversationId, key, saved)
    // Keep showing the local picture / player, so there is no flicker while the signed link is fetched.
    if (entry.previewUrl) {
      rememberPreview(saved.id, entry.previewUrl)
      entry.previewUrl = null // the link now belongs to the saved message: do not revoke it
    }
    discard(key)
  } catch (error) {
    if (entry.controller?.signal.aborted) return // the user cancelled: cancelAttachment() already cleaned up
    handleFailure(key, entry, error)
  }
}

function handleFailure(key: string, entry: PendingUpload, error: unknown): void {
  const store = useChatStore.getState()
  const appError = toAppError(error)
  const text = hasUploadMessage(appError.errorCode, appError.status)
    ? messageForUploadError(appError.errorCode, appError.status, appError.kind)
    : describeSendError(error)

  // The server will never accept this file: remove the bubble and tell the user why.
  if (isRejection(appError.errorCode, appError.status)) {
    store.removePending(entry.conversationId, key)
    discard(key)
    entry.onRejected?.(text)
    return
  }

  // The uploaded file is gone on the server (cleaned up): the next try uploads it again.
  if (appError.errorCode === 'ATTACHMENT_NOT_FOUND') entry.mediaId = null

  store.updatePending(entry.conversationId, key, { localStatus: 'FAILED', errorMessage: text })
  // Not allowed to write here any more: reload the chat so the composer explains why.
  if (appError.kind === 'NOT_FOUND' && appError.errorCode !== 'ATTACHMENT_NOT_FOUND') {
    refreshConversation(entry.conversationId).catch(() => undefined)
  }
}
