import * as chatApi from '../../services/api/chatApi'
import * as mediaApi from '../../services/api/mediaApi'
import { AppError } from '../../services/api/appError'
import { useAuthStore } from '../../store/authStore'
import { useChatStore } from '../../store/chatStore'
import { ME, makeMessage } from '../../test/chatFixtures'
import { fakeTokens } from '../../test/renderWithProviders'
import type { Media } from '../../types/chat'
import { cancelAttachment, isUploadPending, retryAttachment, sendAttachment } from './attachmentActions'
import { getLocalPreview } from './localPreviews'

vi.mock('../../services/api/chatApi')
vi.mock('../../services/api/mediaApi')
const chat = vi.mocked(chatApi)
const media = vi.mocked(mediaApi)

const messagesOf = () => useChatStore.getState().messages['c1'] ?? []
const file = new File(['pixels'], 'holiday.png', { type: 'image/png' })
const input = { file, fileName: 'holiday.png', kind: 'IMAGE' as const, caption: ' Look! ', durationSeconds: null }

function uploaded(id = 'media-9'): Media {
  return {
    id, kind: 'IMAGE', fileName: 'holiday.png', mimeType: 'image/png', sizeBytes: 6, width: 8, height: 8,
    durationSeconds: null, hasThumbnail: true, createdAt: '2026-11-04T10:00:00Z',
  }
}

function savedMessage() {
  return makeMessage({
    id: 'srv-1', senderId: ME, type: 'IMAGE', content: 'Look!', status: 'SENT',
    attachment: { mediaId: 'media-9', kind: 'IMAGE', fileName: 'holiday.png', mimeType: 'image/png', sizeBytes: 6, width: 8, height: 8, durationSeconds: null, hasThumbnail: true },
  })
}

beforeEach(() => {
  vi.resetAllMocks()
  useChatStore.getState().reset()
  useAuthStore.setState({ ...useAuthStore.getState(), user: { ...fakeTokens().user, id: ME }, accessToken: 'a' })
})

describe('sending a file', () => {
  it('shows the message at once, reports upload progress, then sends it with the attachment id and the caption', async () => {
    let finishUpload!: (value: Media) => void
    let progress!: (percent: number) => void
    media.uploadMedia.mockImplementation((_file, _name, _purpose, _duration, options) => {
      progress = options!.onProgress!
      return new Promise((resolve) => (finishUpload = resolve))
    })
    chat.sendMessage.mockResolvedValue(savedMessage())

    const sending = sendAttachment('c1', input)

    // before anything finished
    expect(messagesOf()).toHaveLength(1)
    expect(messagesOf()[0]).toMatchObject({
      type: 'IMAGE', content: 'Look!', localStatus: 'SENDING', uploadProgress: 0, senderId: ME,
      attachment: { fileName: 'holiday.png', mediaId: '' },
    })
    expect(messagesOf()[0].previewUrl).toMatch(/^blob:/)
    expect(useChatStore.getState().summaries['c1'].lastMessage?.type).toBe('IMAGE')

    progress(40)
    expect(messagesOf()[0].uploadProgress).toBe(40)

    finishUpload(uploaded())
    await sending

    expect(media.uploadMedia).toHaveBeenCalledWith(file, 'holiday.png', 'IMAGE', null, expect.anything())
    const body = chat.sendMessage.mock.calls[0][0]
    expect(body).toMatchObject({ conversationId: 'c1', type: 'IMAGE', attachmentId: 'media-9', content: 'Look!' })
    expect(body.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/)

    // the server copy replaced the pending one, and the local picture is kept for it (no flicker)
    expect(messagesOf().map((m) => m.id)).toEqual(['srv-1'])
    expect(messagesOf()[0].localStatus).toBeUndefined()
    expect(getLocalPreview('srv-1')).toMatch(/^blob:/)
  })

  it('sends the voice duration with the upload', async () => {
    media.uploadMedia.mockResolvedValue({ ...uploaded('v-1'), kind: 'VOICE' })
    chat.sendMessage.mockResolvedValue(makeMessage({ senderId: ME, type: 'VOICE' }))

    await sendAttachment('c1', {
      file: new Blob(['a'], { type: 'audio/webm' }), fileName: 'voice-note.webm', kind: 'VOICE', caption: '', durationSeconds: 7,
    })

    expect(media.uploadMedia).toHaveBeenCalledWith(expect.any(Blob), 'voice-note.webm', 'VOICE', 7, expect.anything())
    expect(chat.sendMessage.mock.calls[0][0]).toMatchObject({ type: 'VOICE', attachmentId: 'v-1', content: '' })
  })

  it('a lost connection during the upload marks the message FAILED; Retry uploads again', async () => {
    media.uploadMedia.mockRejectedValueOnce(new AppError('NETWORK', 'Cannot reach the server.'))

    await sendAttachment('c1', input)

    expect(messagesOf()[0].localStatus).toBe('FAILED')
    expect(messagesOf()[0].errorMessage).toMatch(/No connection/)
    const key = messagesOf()[0].idempotencyKey!
    expect(isUploadPending(key)).toBe(true)
    expect(chat.sendMessage).not.toHaveBeenCalled()

    media.uploadMedia.mockResolvedValueOnce(uploaded())
    chat.sendMessage.mockResolvedValueOnce(savedMessage())
    await retryAttachment(key)

    expect(media.uploadMedia).toHaveBeenCalledTimes(2)
    expect(messagesOf().map((m) => m.id)).toEqual(['srv-1'])
    expect(isUploadPending(key)).toBe(false)
  })

  it('when only the message step failed, Retry reuses the SAME key and the SAME attachment id, without uploading again', async () => {
    media.uploadMedia.mockResolvedValue(uploaded())
    chat.sendMessage.mockRejectedValueOnce(new AppError('NETWORK', 'Cannot reach the server.'))

    await sendAttachment('c1', input)

    expect(messagesOf()[0].localStatus).toBe('FAILED')
    const key = messagesOf()[0].idempotencyKey!
    const firstBody = chat.sendMessage.mock.calls[0][0]

    chat.sendMessage.mockResolvedValueOnce(savedMessage())
    await retryAttachment(key)

    expect(media.uploadMedia).toHaveBeenCalledTimes(1) // not uploaded twice
    expect(chat.sendMessage).toHaveBeenCalledTimes(2)
    const secondBody = chat.sendMessage.mock.calls[1][0]
    expect(secondBody.idempotencyKey).toBe(firstBody.idempotencyKey)
    expect(secondBody.attachmentId).toBe('media-9')
    expect(messagesOf().map((m) => m.id)).toEqual(['srv-1'])
  })

  it('removes the bubble and tells the user when the server rejects the file for good', async () => {
    const rejected = vi.fn()
    media.uploadMedia.mockRejectedValue(new AppError('UNKNOWN', 'bad', 415, {}, 'CONTENT_MISMATCH'))

    await sendAttachment('c1', input, rejected)

    expect(messagesOf()).toHaveLength(0)
    expect(rejected).toHaveBeenCalledWith(expect.stringMatching(/not what its name says/))
    expect(chat.sendMessage).not.toHaveBeenCalled()
    expect(useChatStore.getState().summaries['c1'].lastMessage).toBeNull()
  })

  it('shows a clear text when the message itself is refused (blocked) and keeps it FAILED', async () => {
    media.uploadMedia.mockResolvedValue(uploaded())
    chat.sendMessage.mockRejectedValue(new AppError('FORBIDDEN', 'no', 403, {}, 'MESSAGING_NOT_ALLOWED'))

    await sendAttachment('c1', input)

    expect(messagesOf()[0].localStatus).toBe('FAILED')
    expect(messagesOf()[0].errorMessage).toMatch(/can't message this user/)
  })

  it('uploads the file again on Retry when the server lost the uploaded file', async () => {
    media.uploadMedia.mockResolvedValue(uploaded())
    chat.sendMessage.mockRejectedValueOnce(new AppError('NOT_FOUND', 'gone', 404, {}, 'ATTACHMENT_NOT_FOUND'))

    await sendAttachment('c1', input)
    expect(messagesOf()[0].errorMessage).toMatch(/no longer available/)
    const key = messagesOf()[0].idempotencyKey!

    media.uploadMedia.mockResolvedValueOnce(uploaded('media-10'))
    chat.sendMessage.mockResolvedValueOnce(savedMessage())
    await retryAttachment(key)

    expect(media.uploadMedia).toHaveBeenCalledTimes(2)
    expect(chat.sendMessage.mock.calls[1][0].attachmentId).toBe('media-10')
  })

  it('Cancel aborts the upload, removes the message and frees the preview', async () => {
    let signal!: AbortSignal
    media.uploadMedia.mockImplementation((_f, _n, _p, _d, options) => {
      signal = options!.signal!
      return new Promise((_resolve, reject) => {
        signal.addEventListener('abort', () => reject(new Error('aborted')))
      })
    })
    const revoke = vi.spyOn(URL, 'revokeObjectURL')

    const sending = sendAttachment('c1', input)
    const key = messagesOf()[0].idempotencyKey!
    expect(signal.aborted).toBe(false)

    cancelAttachment(key)
    await sending

    expect(signal.aborted).toBe(true)
    expect(messagesOf()).toHaveLength(0)
    expect(isUploadPending(key)).toBe(false)
    expect(revoke).toHaveBeenCalled()
    expect(chat.sendMessage).not.toHaveBeenCalled() // a cancelled upload is never sent as a message
  })

  it('does nothing without a logged in user', async () => {
    useAuthStore.setState({ ...useAuthStore.getState(), user: null })

    await sendAttachment('c1', input)

    expect(messagesOf()).toHaveLength(0)
    expect(media.uploadMedia).not.toHaveBeenCalled()
  })
})
