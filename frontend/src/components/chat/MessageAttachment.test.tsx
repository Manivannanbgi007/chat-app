import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import * as chatApi from '../../services/api/chatApi'
import { ME, makeMediaMessage } from '../../test/chatFixtures'
import { renderWithProviders } from '../../test/renderWithProviders'
import type { ChatMessage } from '../../types/chat'
import { rememberPreview } from '../../features/media/localPreviews'
import MessageBubble from './MessageBubble'

vi.mock('../../services/api/chatApi')
const api = vi.mocked(chatApi)

const inMinutes = (minutes: number) => new Date(Date.now() + minutes * 60_000).toISOString()

function renderBubble(message: ChatMessage, props: Partial<Parameters<typeof MessageBubble>[0]> = {}) {
  const onRetry = vi.fn()
  const onCancel = vi.fn()
  renderWithProviders(<MessageBubble message={message} mine={message.senderId === ME} onRetry={onRetry} onCancel={onCancel} {...props} />)
  return { onRetry, onCancel }
}

beforeEach(() => {
  vi.resetAllMocks()
  api.getAttachmentUrl.mockImplementation(async (_id, variant = 'original') => ({
    url: `https://storage.test/${variant}`,
    expiresAt: inMinutes(5),
  }))
})

describe('media message bubble', () => {
  it('shows the thumbnail of a picture (not the original) and keeps the shape of the picture', async () => {
    renderBubble(makeMediaMessage({ kind: 'IMAGE', hasThumbnail: true, width: 800, height: 400 }, { id: 'm-img' }))

    const image = await screen.findByAltText('holiday.png')
    expect(image).toHaveAttribute('src', 'https://storage.test/thumbnail')
    expect(api.getAttachmentUrl).toHaveBeenCalledWith('m-img', 'thumbnail')
    expect(screen.getByTestId('message').querySelector('[style*="aspect-ratio"]')).toHaveStyle({ aspectRatio: '800 / 400' })
  })

  it('uses the original when the picture has no thumbnail', async () => {
    renderBubble(makeMediaMessage({ kind: 'IMAGE', hasThumbnail: false, fileName: 'anim.webp' }, { id: 'm-webp' }))

    expect(await screen.findByAltText('anim.webp')).toHaveAttribute('src', 'https://storage.test/original')
    expect(api.getAttachmentUrl).toHaveBeenCalledWith('m-webp', 'original')
  })

  it('shows a loading box first and an error with Retry when the link cannot be fetched', async () => {
    api.getAttachmentUrl.mockRejectedValueOnce(new Error('down')).mockRejectedValueOnce(new Error('down'))
    renderBubble(makeMediaMessage({ kind: 'IMAGE' }, { id: 'm-err' }))

    expect(screen.getByRole('status', { name: 'Loading photo' })).toBeInTheDocument()
    expect(await screen.findByText('Could not load photo.', {}, { timeout: 4000 })).toBeInTheDocument()

    api.getAttachmentUrl.mockResolvedValue({ url: 'https://storage.test/after-retry', expiresAt: inMinutes(5) })
    await userEvent.setup().click(screen.getByRole('button', { name: /Retry/ }))
    expect(await screen.findByAltText('holiday.png')).toHaveAttribute('src', 'https://storage.test/after-retry')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument() // pressing Retry must not open the lightbox
  })

  it('opens the original in a lightbox that Escape closes', async () => {
    const user = userEvent.setup()
    renderBubble(makeMediaMessage({ kind: 'IMAGE' }, { id: 'm-light' }))
    await screen.findByAltText('holiday.png')

    await user.click(screen.getByRole('button', { name: 'Open photo holiday.png' }))

    const dialog = await screen.findByRole('dialog', { name: 'Photo: holiday.png' })
    expect(dialog).toBeInTheDocument()
    await waitFor(() => expect(screen.getAllByAltText('holiday.png').some((img) => img.getAttribute('src') === 'https://storage.test/original')).toBe(true))
    expect(screen.getByRole('button', { name: 'Close photo' })).toHaveFocus()

    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('shows a video player that loads only the metadata first', async () => {
    renderBubble(makeMediaMessage({ kind: 'VIDEO', fileName: 'clip.mp4', mimeType: 'video/mp4', hasThumbnail: false }, { id: 'm-vid' }))

    const video = await screen.findByLabelText('Video clip.mp4')
    expect(video.tagName).toBe('VIDEO')
    expect(video).toHaveAttribute('src', 'https://storage.test/original')
    expect(video).toHaveAttribute('preload', 'metadata')
    expect(video).toHaveAttribute('controls')
  })

  it('shows a voice note as a player with the duration', async () => {
    renderBubble(makeMediaMessage({ kind: 'VOICE', fileName: 'voice-note.webm', durationSeconds: 75, hasThumbnail: false }, { id: 'm-voice' }))

    const player = await screen.findByLabelText('Voice message')
    expect(player.tagName).toBe('AUDIO')
    expect(player).toHaveAttribute('src', 'https://storage.test/original')
    expect(screen.getByText('1:15')).toBeInTheDocument()
  })

  it('shows an audio file with its name', async () => {
    renderBubble(makeMediaMessage({ kind: 'AUDIO', fileName: 'song.mp3', durationSeconds: null, hasThumbnail: false }, { id: 'm-audio' }))

    const player = await screen.findByLabelText('song.mp3')
    expect(player.tagName).toBe('AUDIO')
  })

  it('shows a document as a card with name and size, and asks for a FRESH link when Download is pressed', async () => {
    const user = userEvent.setup()
    const clicked: string[] = []
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      clicked.push(`${this.href}|${this.download}`)
    })
    renderBubble(makeMediaMessage({ kind: 'DOCUMENT', fileName: 'report.pdf', sizeBytes: 2 * 1024 * 1024, hasThumbnail: false }, { id: 'm-doc' }))

    expect(screen.getByText('report.pdf')).toBeInTheDocument()
    expect(screen.getByText('2 MB')).toBeInTheDocument()
    expect(api.getAttachmentUrl).not.toHaveBeenCalled() // no link is fetched until someone wants the file

    await user.click(screen.getByRole('button', { name: 'Download report.pdf' }))

    await waitFor(() => expect(clicked).toEqual(['https://storage.test/original|report.pdf']))
    expect(api.getAttachmentUrl).toHaveBeenCalledWith('m-doc', 'original')

    await user.click(screen.getByRole('button', { name: 'Download report.pdf' }))
    await waitFor(() => expect(api.getAttachmentUrl).toHaveBeenCalledTimes(2)) // a new link every time
    click.mockRestore()
  })

  it('tells the user when the download cannot start', async () => {
    api.getAttachmentUrl.mockRejectedValue(new Error('down'))
    renderBubble(makeMediaMessage({ kind: 'DOCUMENT', fileName: 'report.pdf', hasThumbnail: false }, { id: 'm-doc2' }))

    await userEvent.setup().click(screen.getByRole('button', { name: 'Download report.pdf' }))

    expect(await screen.findByText(/download could not start/)).toBeInTheDocument()
  })

  it('shows the caption under the file, and no empty text line without a caption', async () => {
    const { unmount } = renderBubbleAndReturn(makeMediaMessage({ kind: 'IMAGE' }, { id: 'm-cap', content: 'Sunset at the beach' }))
    expect(screen.getByText('Sunset at the beach')).toBeInTheDocument()
    unmount()

    renderBubble(makeMediaMessage({ kind: 'DOCUMENT', hasThumbnail: false }, { id: 'm-nocap', content: '' }))
    expect(screen.getByTestId('message').querySelectorAll('p.whitespace-pre-wrap')).toHaveLength(0)
  })

  it('shows the file from this computer for my message that is still being sent, without asking the server', () => {
    const pending: ChatMessage = {
      ...makeMediaMessage({ kind: 'IMAGE', mediaId: '' }, { id: 'local-k1', senderId: ME }),
      localStatus: 'SENDING',
      idempotencyKey: 'k1',
      previewUrl: 'blob:local-picture',
      uploadProgress: 35,
    }
    renderBubble(pending)

    expect(screen.getByAltText('holiday.png')).toHaveAttribute('src', 'blob:local-picture')
    expect(api.getAttachmentUrl).not.toHaveBeenCalled()
  })

  it('keeps showing the local picture after the message was saved (no flicker, no extra request)', () => {
    rememberPreview('srv-77', 'blob:kept-picture')
    renderBubble(makeMediaMessage({ kind: 'IMAGE' }, { id: 'srv-77', senderId: ME, status: 'SENT' }))

    expect(screen.getByAltText('holiday.png')).toHaveAttribute('src', 'blob:kept-picture')
    expect(api.getAttachmentUrl).not.toHaveBeenCalled()
  })
})

// Same as renderBubble but returns the render result (for unmount).
function renderBubbleAndReturn(message: ChatMessage) {
  return renderWithProviders(<MessageBubble message={message} mine={false} onRetry={vi.fn()} />)
}

describe('upload progress, cancel and retry on the bubble', () => {
  const uploading = (progress: number): ChatMessage => ({
    ...makeMediaMessage({ kind: 'IMAGE', mediaId: '' }, { id: 'local-k2', senderId: ME }),
    localStatus: 'SENDING',
    idempotencyKey: 'k2',
    previewUrl: 'blob:local',
    uploadProgress: progress,
  })

  it('shows a progress bar with the percentage and a Cancel button while uploading', async () => {
    const { onCancel } = renderBubble(uploading(42))

    const bar = screen.getByRole('progressbar', { name: 'Upload progress' })
    expect(bar).toHaveAttribute('aria-valuenow', '42')
    expect(screen.getByText('Uploading 42%')).toBeInTheDocument()

    await userEvent.setup().click(screen.getByRole('button', { name: 'Cancel upload' }))
    expect(onCancel).toHaveBeenCalledTimes(1)
  })

  it('hides the progress bar once the upload is complete (the message is being sent)', () => {
    renderBubble(uploading(100))

    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument()
  })

  it('a failed media message offers Retry and Discard', async () => {
    const user = userEvent.setup()
    const failed: ChatMessage = { ...uploading(100), localStatus: 'FAILED', errorMessage: 'No connection. Press Retry when you are back online.' }
    const { onRetry, onCancel } = renderBubble(failed)

    expect(screen.getByText(/No connection/)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Retry' }))
    await user.click(screen.getByRole('button', { name: 'Discard message' }))

    expect(onRetry).toHaveBeenCalledWith(failed)
    expect(onCancel).toHaveBeenCalledWith(failed)
  })
})
