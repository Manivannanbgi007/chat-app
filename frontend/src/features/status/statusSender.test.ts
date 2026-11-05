import { AppError } from '../../services/api/appError'
import { uploadMedia } from '../../services/api/mediaApi'
import { createStatus } from '../../services/api/statusApi'
import { makeStatus } from '../../test/statusFixtures'
import type { Media } from '../../types/chat'
import { StatusSender } from './statusSender'

vi.mock('../../services/api/mediaApi')
vi.mock('../../services/api/statusApi')

const media: Media = {
  id: 'm-1',
  kind: 'IMAGE',
  fileName: 'sea.png',
  mimeType: 'image/png',
  sizeBytes: 10,
  width: 4,
  height: 3,
  durationSeconds: null,
  hasThumbnail: true,
  createdAt: '2026-11-04T10:00:00Z',
}
const file = new Blob(['png'], { type: 'image/png' })
const input = { file, fileName: 'sea.png', kind: 'IMAGE' as const, caption: '  Sea  ', durationSeconds: null }

beforeEach(() => {
  vi.resetAllMocks()
})

describe('StatusSender', () => {
  it('posts a text status with trimmed text and keeps the same key for a retry after an error', async () => {
    let n = 0
    const sender = new StatusSender(() => `key-${++n}`)
    vi.mocked(createStatus).mockRejectedValueOnce(new AppError('NETWORK', 'offline')).mockResolvedValueOnce(makeStatus())

    await expect(sender.postText('  hello  ', '#0F766E')).rejects.toThrow('offline')
    await sender.postText('  hello  ', '#0F766E')

    expect(createStatus).toHaveBeenNthCalledWith(1, { type: 'TEXT', text: 'hello', backgroundColor: '#0F766E', idempotencyKey: 'key-1' })
    expect(vi.mocked(createStatus).mock.calls[1][0]).toMatchObject({ idempotencyKey: 'key-1' })
  })

  it('a new draft gets a new key', () => {
    expect(new StatusSender().idempotencyKey).not.toBe(new StatusSender().idempotencyKey)
  })

  it('uploads the file with progress, then posts the status with the media id and the trimmed caption', async () => {
    vi.mocked(uploadMedia).mockImplementation(async (_file, _name, _purpose, _duration, options) => {
      options?.onProgress?.(40)
      options?.onProgress?.(100)
      return media
    })
    vi.mocked(createStatus).mockResolvedValue(makeStatus({ type: 'IMAGE' }))
    const progress: number[] = []
    const sender = new StatusSender(() => 'key-x')

    await sender.postMedia(input, (percent) => progress.push(percent))

    expect(uploadMedia).toHaveBeenCalledWith(file, 'sea.png', 'IMAGE', null, expect.objectContaining({ signal: expect.any(AbortSignal) }))
    expect(progress).toEqual([40, 100])
    expect(createStatus).toHaveBeenCalledWith({ type: 'IMAGE', mediaId: 'm-1', caption: 'Sea', idempotencyKey: 'key-x' })
    expect(sender.hasUploaded()).toBe(true)
  })

  it('does not send an empty caption', async () => {
    vi.mocked(uploadMedia).mockResolvedValue(media)
    vi.mocked(createStatus).mockResolvedValue(makeStatus())
    await new StatusSender(() => 'k').postMedia({ ...input, caption: '   ' })
    expect(createStatus).toHaveBeenCalledWith({ type: 'IMAGE', mediaId: 'm-1', idempotencyKey: 'k' })
  })

  it('a retry after a failed post does not upload the file again', async () => {
    vi.mocked(uploadMedia).mockResolvedValue(media)
    vi.mocked(createStatus).mockRejectedValueOnce(new AppError('SERVER', 'busy', 503)).mockResolvedValueOnce(makeStatus())
    const sender = new StatusSender(() => 'k')

    await expect(sender.postMedia(input)).rejects.toThrow('busy')
    await sender.postMedia(input)

    expect(uploadMedia).toHaveBeenCalledTimes(1)
    expect(createStatus).toHaveBeenCalledTimes(2)
  })

  it('uploads again when the server says the uploaded file is gone', async () => {
    vi.mocked(uploadMedia).mockResolvedValueOnce(media).mockResolvedValueOnce({ ...media, id: 'm-2' })
    vi.mocked(createStatus)
      .mockRejectedValueOnce(new AppError('NOT_FOUND', 'gone', 404, {}, 'ATTACHMENT_NOT_FOUND'))
      .mockResolvedValueOnce(makeStatus())
    const sender = new StatusSender(() => 'k')

    await expect(sender.postMedia(input)).rejects.toThrow('gone')
    expect(sender.hasUploaded()).toBe(false)
    await sender.postMedia(input)

    expect(uploadMedia).toHaveBeenCalledTimes(2)
    expect(vi.mocked(createStatus).mock.calls[1][0]).toMatchObject({ mediaId: 'm-2', idempotencyKey: 'k' })
  })

  it('cancel aborts the running upload', async () => {
    vi.mocked(uploadMedia).mockImplementation(
      (_file, _name, _purpose, _duration, options) =>
        new Promise((_resolve, reject) => options?.signal?.addEventListener('abort', () => reject(new Error('canceled')))),
    )
    const sender = new StatusSender()
    const posting = sender.postMedia(input)

    sender.cancel()

    await expect(posting).rejects.toThrow('canceled')
    expect(sender.wasCancelled()).toBe(true)
    expect(createStatus).not.toHaveBeenCalled()
  })
})
