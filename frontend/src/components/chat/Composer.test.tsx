import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createRef } from 'react'
import { useVoiceRecorder } from '../../features/media/useVoiceRecorder'
import { webSocketManager } from '../../services/websocket/websocketManager'
import Composer, { type ComposerHandle } from './Composer'

vi.mock('../../services/websocket/websocketManager', () => ({
  webSocketManager: { publish: vi.fn().mockReturnValue(true) },
}))

// The microphone is replaced by a stand-in whose state each test sets (the real hook has its own tests).
vi.mock('../../features/media/useVoiceRecorder', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../../features/media/useVoiceRecorder')>()),
  useVoiceRecorder: vi.fn(),
}))

const publish = vi.mocked(webSocketManager.publish)
const mockedRecorder = vi.mocked(useVoiceRecorder)

beforeEach(() => {
  publish.mockClear()
  mockedRecorder.mockReset()
  mockedRecorder.mockReturnValue({
    status: 'idle',
    seconds: 0,
    error: null,
    clearError: vi.fn(),
    start: vi.fn().mockResolvedValue(undefined),
    finish: vi.fn().mockResolvedValue(null),
    cancel: vi.fn(),
  })
})

describe('Composer', () => {
  it('cannot send a blank message', async () => {
    const onSend = vi.fn()
    render(<Composer conversationId="c1" onSend={onSend} />)
    const user = userEvent.setup()

    expect(screen.getByRole('button', { name: 'Send message' })).toBeDisabled()
    await user.type(screen.getByLabelText('Message'), '   ')
    expect(screen.getByRole('button', { name: 'Send message' })).toBeDisabled()
    await user.keyboard('{Enter}')

    expect(onSend).not.toHaveBeenCalled()
  })

  it('Enter sends and clears the box', async () => {
    const onSend = vi.fn()
    render(<Composer conversationId="c1" onSend={onSend} />)
    const user = userEvent.setup()

    await user.type(screen.getByLabelText('Message'), 'hello{Enter}')

    expect(onSend).toHaveBeenCalledWith('hello')
    expect(screen.getByLabelText('Message')).toHaveValue('')
  })

  it('Shift+Enter adds a new line instead of sending', async () => {
    const onSend = vi.fn()
    render(<Composer conversationId="c1" onSend={onSend} />)
    const user = userEvent.setup()

    await user.type(screen.getByLabelText('Message'), 'line one{Shift>}{Enter}{/Shift}line two')

    expect(onSend).not.toHaveBeenCalled()
    expect(screen.getByLabelText('Message')).toHaveValue('line one\nline two')
  })

  it('the Send button sends', async () => {
    const onSend = vi.fn()
    render(<Composer conversationId="c1" onSend={onSend} />)
    const user = userEvent.setup()

    await user.type(screen.getByLabelText('Message'), 'via button')
    await user.click(screen.getByRole('button', { name: 'Send message' }))

    expect(onSend).toHaveBeenCalledWith('via button')
  })

  it('limits the text to 4000 characters and shows a counter near the limit', async () => {
    render(<Composer conversationId="c1" onSend={vi.fn()} />)
    const box = screen.getByLabelText('Message')

    expect(box).toHaveAttribute('maxlength', '4000')
    expect(screen.queryByText(/ \/ 4000/)).not.toBeInTheDocument()

    await userEvent.setup().click(box)
    await userEvent.paste('a'.repeat(3600))
    expect(screen.getByText('3600 / 4000')).toBeInTheDocument()
  })

  it('tells the other person "typing", once, and "stopped" when the message is sent', async () => {
    render(<Composer conversationId="c1" onSend={vi.fn()} />)
    const user = userEvent.setup()

    await user.type(screen.getByLabelText('Message'), 'abc')
    expect(publish).toHaveBeenCalledTimes(1)
    expect(publish).toHaveBeenLastCalledWith('/app/chat.typing', { conversationId: 'c1', typing: true })

    await user.keyboard('{Enter}')
    expect(publish).toHaveBeenCalledTimes(2)
    expect(publish).toHaveBeenLastCalledWith('/app/chat.typing', { conversationId: 'c1', typing: false })
  })

  it('says "stopped typing" when the box is emptied or the chat is closed', async () => {
    const { unmount } = render(<Composer conversationId="c1" onSend={vi.fn()} />)
    const user = userEvent.setup()

    await user.type(screen.getByLabelText('Message'), 'a')
    await user.clear(screen.getByLabelText('Message'))
    expect(publish).toHaveBeenLastCalledWith('/app/chat.typing', { conversationId: 'c1', typing: false })

    await user.type(screen.getByLabelText('Message'), 'b')
    publish.mockClear()
    unmount()
    expect(publish).toHaveBeenCalledWith('/app/chat.typing', { conversationId: 'c1', typing: false })
  })
})

// ---------------------------------------------------------------------------------------------
// Files and voice notes
// ---------------------------------------------------------------------------------------------

describe('Composer attachments', () => {
  const png = () => new File(['pixels'], 'holiday.png', { type: 'image/png' })

  async function pick(label: string, file: File) {
    // applyAccept off: the "accept" filter of the hidden input is only a hint for the file dialog
    const user = userEvent.setup({ applyAccept: false })
    await user.upload(screen.getByLabelText(label, { selector: 'input' }), file)
    return user
  }

  it('the paperclip opens a menu with Photo, Video, Document and Audio', async () => {
    render(<Composer conversationId="c1" onSend={vi.fn()} />)
    const user = userEvent.setup()

    await user.click(screen.getByRole('button', { name: 'Attach a file' }))

    const menu = screen.getByRole('menu', { name: 'Attach a file' })
    expect(within(menu).getAllByRole('menuitem').map((item) => item.textContent)).toEqual(['Photo', 'Video', 'Document', 'Audio'])
  })

  it('a picked photo gets a preview with name and size, and can be sent without any text', async () => {
    const onSendAttachment = vi.fn()
    render(<Composer conversationId="c1" onSend={vi.fn()} onSendAttachment={onSendAttachment} />)
    const user = await pick('Choose a photo', png())

    const tray = screen.getByTestId('attachment-preview')
    expect(within(tray).getByText('holiday.png')).toBeInTheDocument()
    expect(within(tray).getByText(/6 B/)).toBeInTheDocument()
    expect(within(tray).getByAltText('Preview of holiday.png')).toBeInTheDocument()
    expect(screen.getByLabelText('Message')).toHaveAttribute('placeholder', 'Add a caption')

    await user.click(screen.getByRole('button', { name: 'Send message' }))

    expect(onSendAttachment).toHaveBeenCalledTimes(1)
    expect(onSendAttachment.mock.calls[0][0]).toMatchObject({ fileName: 'holiday.png', kind: 'IMAGE', caption: '', durationSeconds: null })
    expect(screen.queryByTestId('attachment-preview')).not.toBeInTheDocument()
  })

  it('the text box is the caption (Enter sends the file with it, and the box is cleared)', async () => {
    const onSend = vi.fn()
    const onSendAttachment = vi.fn()
    render(<Composer conversationId="c1" onSend={onSend} onSendAttachment={onSendAttachment} />)
    const user = await pick('Choose a document', new File(['%PDF'], 'report.pdf', { type: 'application/pdf' }))

    await user.type(screen.getByLabelText('Message'), 'Please read{Enter}')

    expect(onSendAttachment.mock.calls[0][0]).toMatchObject({ kind: 'DOCUMENT', caption: 'Please read' })
    expect(onSend).not.toHaveBeenCalled() // not sent as plain text as well
    expect(screen.getByLabelText('Message')).toHaveValue('')
  })

  it('the X removes the attachment', async () => {
    render(<Composer conversationId="c1" onSend={vi.fn()} onSendAttachment={vi.fn()} />)
    const user = await pick('Choose a photo', png())

    await user.click(screen.getByRole('button', { name: 'Remove attachment' }))

    expect(screen.queryByTestId('attachment-preview')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Send message' })).toBeDisabled()
  })

  it('refuses a file that is too large, before uploading anything', async () => {
    const onSendAttachment = vi.fn()
    render(<Composer conversationId="c1" onSend={vi.fn()} onSendAttachment={onSendAttachment} />)
    const big = new File(['x'], 'huge.png', { type: 'image/png' })
    Object.defineProperty(big, 'size', { value: 11 * 1024 * 1024 })

    await pick('Choose a photo', big)

    expect(screen.getByRole('alert')).toHaveTextContent(/too large.*10 MB/)
    expect(screen.queryByTestId('attachment-preview')).not.toBeInTheDocument()
    expect(onSendAttachment).not.toHaveBeenCalled()
  })

  it('refuses a file of the wrong type', async () => {
    render(<Composer conversationId="c1" onSend={vi.fn()} onSendAttachment={vi.fn()} />)

    await pick('Choose a document', new File(['MZ'], 'virus.exe', { type: 'application/x-msdownload' }))

    expect(screen.getByRole('alert')).toHaveTextContent(/cannot be sent as a document/)
    expect(screen.queryByTestId('attachment-preview')).not.toBeInTheDocument()
  })

  it('shows the reason when the server rejects the file for good (the callback of onSendAttachment)', async () => {
    const onSendAttachment = vi.fn((_input, onRejected: (message: string) => void) => onRejected('The file is not what its name says, so it was rejected.'))
    render(<Composer conversationId="c1" onSend={vi.fn()} onSendAttachment={onSendAttachment} />)
    const user = await pick('Choose a photo', png())

    await user.click(screen.getByRole('button', { name: 'Send message' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(/not what its name says/)
  })

  it('a picture pasted into the box becomes the attachment, plain text paste is left alone', () => {
    render(<Composer conversationId="c1" onSend={vi.fn()} onSendAttachment={vi.fn()} />)
    const box = screen.getByLabelText('Message')

    fireEvent.paste(box, { clipboardData: { files: [] } })
    expect(screen.queryByTestId('attachment-preview')).not.toBeInTheDocument()

    fireEvent.paste(box, { clipboardData: { files: [png()] } })
    expect(screen.getByTestId('attachment-preview')).toBeInTheDocument()
  })

  it('a file given to the composer through its handle (drag and drop) is attached by its type', async () => {
    const ref = createRef<ComposerHandle>()
    render(<Composer ref={ref} conversationId="c1" onSend={vi.fn()} onSendAttachment={vi.fn()} />)

    act(() => ref.current!.attachFile(new File(['a'], 'clip.mp4', { type: 'video/mp4' })))
    expect(await screen.findByText('clip.mp4')).toBeInTheDocument()

    act(() => ref.current!.attachFile(new File(['MZ'], 'bad.exe', { type: 'application/octet-stream' })))
    expect(await screen.findByRole('alert')).toBeInTheDocument()
  })
})

describe('Composer voice notes', () => {
  function recorderState(overrides: Partial<ReturnType<typeof useVoiceRecorder>> = {}) {
    return {
      status: 'idle' as const,
      seconds: 0,
      error: null,
      clearError: vi.fn(),
      start: vi.fn().mockResolvedValue(undefined),
      finish: vi.fn().mockResolvedValue(null),
      cancel: vi.fn(),
      ...overrides,
    }
  }

  it('shows the microphone when the box is empty and the Send arrow works as before once there is text', async () => {
    mockedRecorder.mockReturnValue(recorderState())
    render(<Composer conversationId="c1" onSend={vi.fn()} />)

    expect(screen.getByRole('button', { name: 'Record a voice note' })).toBeInTheDocument()
    await userEvent.setup().type(screen.getByLabelText('Message'), 'hi')
    expect(screen.queryByRole('button', { name: 'Record a voice note' })).not.toBeInTheDocument()
  })

  it('starts recording when the microphone is pressed', async () => {
    const state = recorderState()
    mockedRecorder.mockReturnValue(state)
    render(<Composer conversationId="c1" onSend={vi.fn()} />)

    await userEvent.setup().click(screen.getByRole('button', { name: 'Record a voice note' }))

    expect(state.start).toHaveBeenCalled()
  })

  it('while recording it shows the time with Cancel and Send; Cancel discards', async () => {
    const state = recorderState({ status: 'recording', seconds: 12 })
    mockedRecorder.mockReturnValue(state)
    render(<Composer conversationId="c1" onSend={vi.fn()} />)

    expect(screen.getByRole('group', { name: 'Recording a voice note' })).toHaveTextContent('Recording 0:12')
    expect(screen.queryByLabelText('Message')).not.toBeInTheDocument()

    await userEvent.setup().click(screen.getByRole('button', { name: 'Cancel recording' }))
    expect(state.cancel).toHaveBeenCalled()
  })

  it('Send stops the recording and sends it as a VOICE message with the measured duration', async () => {
    const blob = new Blob(['voice'], { type: 'audio/webm' })
    const state = recorderState({
      status: 'recording',
      seconds: 4,
      finish: vi.fn().mockResolvedValue({ blob, durationSeconds: 4, mimeType: 'audio/webm', fileName: 'voice-note.webm' }),
    })
    mockedRecorder.mockReturnValue(state)
    const onSendAttachment = vi.fn()
    render(<Composer conversationId="c1" onSend={vi.fn()} onSendAttachment={onSendAttachment} />)

    await userEvent.setup().click(screen.getByRole('button', { name: 'Send voice note' }))

    await waitFor(() => expect(onSendAttachment).toHaveBeenCalledTimes(1))
    expect(onSendAttachment.mock.calls[0][0]).toEqual({
      file: blob, fileName: 'voice-note.webm', kind: 'VOICE', caption: '', durationSeconds: 4,
    })
  })

  it('sends nothing when the recording was too short (finish returns null)', async () => {
    const state = recorderState({ status: 'recording', seconds: 0 })
    mockedRecorder.mockReturnValue(state)
    const onSendAttachment = vi.fn()
    render(<Composer conversationId="c1" onSend={vi.fn()} onSendAttachment={onSendAttachment} />)

    await userEvent.setup().click(screen.getByRole('button', { name: 'Send voice note' }))

    expect(state.finish).toHaveBeenCalled()
    expect(onSendAttachment).not.toHaveBeenCalled()
  })

  it('shows the recorder problem (denied permission) and can dismiss it', async () => {
    const state = recorderState({ error: 'Microphone access was denied. Allow the microphone in your browser settings and try again.' })
    mockedRecorder.mockReturnValue(state)
    render(<Composer conversationId="c1" onSend={vi.fn()} />)

    expect(screen.getByRole('alert')).toHaveTextContent(/denied/)
    await userEvent.setup().click(screen.getByRole('button', { name: 'Dismiss' }))
    expect(state.clearError).toHaveBeenCalled()
  })
})
