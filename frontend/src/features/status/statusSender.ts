// Posts ONE status (text, photo or video), safely, also when the user has to press Post again.
//
// - The idempotency key is made once per draft and reused for every try. If an earlier try was saved but its
//   answer got lost (network), the server returns the saved status instead of posting a second one.
// - A photo / video is uploaded first (with progress, can be cancelled). A retry skips the upload when it already
//   worked, so a file is never uploaded twice. If the server says the uploaded file is gone (or used elsewhere),
//   the next try uploads it again.
// A composer creates one StatusSender when it opens; a new draft (the composer opened again) gets a new one.
import { toAppError } from '../../services/api/appError'
import { uploadMedia } from '../../services/api/mediaApi'
import { createStatus } from '../../services/api/statusApi'
import type { Status } from '../../types/status'

export interface MediaStatusInput {
  file: Blob
  fileName: string
  kind: 'IMAGE' | 'VIDEO'
  caption: string
  durationSeconds: number | null // measured by the browser for videos (optional)
}

export class StatusSender {
  readonly idempotencyKey: string
  private mediaId: string | null = null
  private controller: AbortController | null = null

  constructor(makeKey: () => string = () => crypto.randomUUID()) {
    this.idempotencyKey = makeKey()
  }

  postText(text: string, backgroundColor: string): Promise<Status> {
    return createStatus({ type: 'TEXT', text: text.trim(), backgroundColor, idempotencyKey: this.idempotencyKey })
  }

  async postMedia(input: MediaStatusInput, onProgress?: (percent: number) => void): Promise<Status> {
    if (!this.mediaId) {
      this.controller = new AbortController()
      const media = await uploadMedia(input.file, input.fileName, input.kind, input.durationSeconds, {
        signal: this.controller.signal,
        onProgress,
      })
      this.mediaId = media.id
    }
    const caption = input.caption.trim()
    try {
      return await createStatus({
        type: input.kind,
        mediaId: this.mediaId,
        ...(caption ? { caption } : {}),
        idempotencyKey: this.idempotencyKey,
      })
    } catch (error) {
      const code = toAppError(error).errorCode
      if (code === 'ATTACHMENT_NOT_FOUND' || code === 'ATTACHMENT_ALREADY_USED') this.mediaId = null
      throw error
    }
  }

  // Stops an upload that is still running (the post then fails with a cancelled error).
  cancel(): void {
    this.controller?.abort()
  }

  // Was the last upload stopped by cancel()? (So the composer does not show it as an error.)
  wasCancelled(): boolean {
    return this.controller?.signal.aborted ?? false
  }

  // Is the file already on the server (so Post only needs to send the status)?
  hasUploaded(): boolean {
    return this.mediaId !== null
  }
}
