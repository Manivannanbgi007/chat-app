import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { Mock } from 'vitest'
import { AppError } from '../../services/api/appError'
import { uploadMedia } from '../../services/api/mediaApi'
import { createStatus } from '../../services/api/statusApi'
import { renderWithProviders } from '../../test/renderWithProviders'
import { makePhotoStatus } from '../../test/statusFixtures'
import type { Media } from '../../types/chat'
import type { Status } from '../../types/status'
import MediaStatusComposer from './MediaStatusComposer'

vi.mock('../../services/api/mediaApi')
vi.mock('../../services/api/statusApi')

const media: Media = {
  id: 'm-1',
  kind: 'IMAGE',
  fileName: 'sea.png',
  mimeType: 'image/png',
  sizeBytes: 3,
  width: 4,
  height: 3,
  durationSeconds: null,
  hasThumbnail: true,
  createdAt: '2026-11-04T10:00:00Z',
}

let onPosted: Mock<(status: Status) => void>
let onClose: Mock<() => void>

function openWith(file = new File(['png'], 'sea.png', { type: 'image/png' }), kind: 'IMAGE' | 'VIDEO' = 'IMAGE') {
  renderWithProviders(<MediaStatusComposer file={file} kind={kind} onPosted={onPosted} onClose={onClose} />)
}

beforeEach(() => {
  vi.resetAllMocks()
  onPosted = vi.fn()
  onClose = vi.fn()
})

describe('MediaStatusComposer', () => {
  it('previews the photo, uploads it with progress and posts it with the caption', async () => {
    let finishUpload: (value: Media) => void = () => undefined
    vi.mocked(uploadMedia).mockImplementation((_file, _name, _purpose, _duration, options) => {
      options?.onProgress?.(35)
      return new Promise((resolve) => {
        finishUpload = resolve
      })
    })
    const saved = makePhotoStatus()
    vi.mocked(createStatus).mockResolvedValue(saved)
    openWith()

    expect(screen.getByRole('img', { name: 'Preview of the photo' })).toHaveAttribute('src', expect.stringMatching(/^blob:/))
    await userEvent.type(screen.getByRole('textbox', { name: 'Caption' }), 'The sea')
    await userEvent.click(screen.getByRole('button', { name: 'Post' }))

    expect(await screen.findByRole('progressbar', { name: 'Upload progress' })).toHaveAttribute('aria-valuenow', '35')
    expect(uploadMedia).toHaveBeenCalledWith(expect.any(File), 'sea.png', 'IMAGE', null, expect.any(Object))
    finishUpload(media)

    await waitFor(() => expect(onPosted).toHaveBeenCalledWith(saved))
    expect(createStatus).toHaveBeenCalledWith(expect.objectContaining({ type: 'IMAGE', mediaId: 'm-1', caption: 'The sea' }))
  })

  it('shows a video preview for videos', () => {
    openWith(new File(['mp4'], 'clip.mp4', { type: 'video/mp4' }), 'VIDEO')
    expect(screen.getByLabelText('Preview of the video').tagName).toBe('VIDEO')
    expect(screen.getByRole('dialog', { name: 'New video status' })).toBeInTheDocument()
  })

  it('explains refused files and the limit of statuses', async () => {
    vi.mocked(uploadMedia).mockRejectedValueOnce(new AppError('UNKNOWN', 'x', 415, {}, 'CONTENT_MISMATCH'))
    openWith()
    await userEvent.click(screen.getByRole('button', { name: 'Post' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('The file is not what its name says')

    vi.mocked(uploadMedia).mockResolvedValue(media)
    vi.mocked(createStatus).mockRejectedValueOnce(new AppError('UNKNOWN', 'x', 409, {}, 'STATUS_LIMIT_REACHED'))
    await userEvent.click(screen.getByRole('button', { name: 'Post' }))
    expect(await screen.findByText(/30 active status updates/)).toBeInTheDocument()
  })

  it('uploads again (same key) when the server lost the uploaded file', async () => {
    vi.mocked(uploadMedia).mockResolvedValueOnce(media).mockResolvedValueOnce({ ...media, id: 'm-2' })
    vi.mocked(createStatus)
      .mockRejectedValueOnce(new AppError('NOT_FOUND', 'gone', 404, {}, 'ATTACHMENT_NOT_FOUND'))
      .mockResolvedValueOnce(makePhotoStatus())
    openWith()

    await userEvent.click(screen.getByRole('button', { name: 'Post' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(/no longer available/)
    await userEvent.click(screen.getByRole('button', { name: 'Post' }))

    await waitFor(() => expect(onPosted).toHaveBeenCalled())
    expect(uploadMedia).toHaveBeenCalledTimes(2)
    const keys = vi.mocked(createStatus).mock.calls.map((call) => call[0].idempotencyKey)
    expect(keys[1]).toBe(keys[0])
  })

  it('Cancel stops the upload and lets the user try again', async () => {
    vi.mocked(uploadMedia).mockImplementation(
      (_file, _name, _purpose, _duration, options) =>
        new Promise((_resolve, reject) => {
          options?.onProgress?.(10)
          options?.signal?.addEventListener('abort', () => reject(new Error('canceled')))
        }),
    )
    openWith()

    await userEvent.click(screen.getByRole('button', { name: 'Post' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Cancel' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Upload cancelled')
    expect(screen.getByRole('button', { name: 'Post' })).toBeEnabled()
    expect(createStatus).not.toHaveBeenCalled()
  })

  it('closes with the X', async () => {
    openWith()
    await userEvent.click(screen.getByRole('button', { name: 'Close' }))
    expect(onClose).toHaveBeenCalled()
  })
})
