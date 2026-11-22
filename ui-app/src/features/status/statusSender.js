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

export class StatusSender {
  constructor(makeKey = () => crypto.randomUUID()) {
    this.idempotencyKey = makeKey()
    this.mediaId = null
    this.controller = null
  }

  postText(text, backgroundColor) {
    return createStatus({ type: 'TEXT', text: text.trim(), backgroundColor, idempotencyKey: this.idempotencyKey })
  }

  // input: { file (Blob), fileName, kind ('IMAGE' | 'VIDEO'), caption, durationSeconds (videos, optional; else null) }
  async postMedia(input, onProgress) {
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
  cancel() {
    this.controller?.abort()
  }

  // Was the last upload stopped by cancel()? (So the composer does not show it as an error.)
  wasCancelled() {
    return this.controller?.signal.aborted ?? false
  }

  // Is the file already on the server (so Post only needs to send the status)?
  hasUploaded() {
    return this.mediaId !== null
  }
}
