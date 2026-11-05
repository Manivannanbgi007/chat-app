import { Mic, Send } from 'lucide-react'
import { useEffect, useImperativeHandle, useRef, useState, type ClipboardEvent, type KeyboardEvent, type Ref } from 'react'
import type { AttachmentInput } from '../../features/media/attachmentActions'
import { MEDIA_RULES, acceptAttribute, guessKind, validateFile } from '../../features/media/mediaRules'
import { useVoiceRecorder, type VoiceResult } from '../../features/media/useVoiceRecorder'
import { TypingSender } from '../../features/messaging/typing'
import { webSocketManager } from '../../services/websocket/websocketManager'
import type { MediaKind } from '../../types/chat'
import AttachMenu from './AttachMenu'
import AttachmentPreviewTray from './AttachmentPreviewTray'
import VoiceRecorderBar from './VoiceRecorderBar'

const MAX_LENGTH = 4000
const SHOW_COUNTER_FROM = 3500
const PICKER_KINDS: Exclude<MediaKind, 'VOICE'>[] = ['IMAGE', 'VIDEO', 'DOCUMENT', 'AUDIO']

// What the chat window can ask the composer to do (for a file dropped on the window).
export interface ComposerHandle {
  attachFile: (file: File) => void
}

interface PickedFile {
  file: File
  kind: MediaKind
  imageUrl: string | null // a temporary link for the preview of a picture
}

// The box at the bottom of a chat: type, Enter to send, Shift+Enter for a new line.
// Files: the paperclip picks a photo / video / document / audio file, which gets a preview and a caption (the text
// box). The microphone records a voice note. Pasting a picture or dropping a file on the window also works.
// While the user types, the other person is told "typing..." (see features/messaging/typing.ts).
export default function Composer({
  conversationId,
  onSend,
  onSendAttachment,
  ref,
}: {
  conversationId: string
  onSend: (text: string) => void
  // Sends a file message. `onRejected` is called if the server refuses the file for good.
  onSendAttachment?: (input: AttachmentInput, onRejected: (message: string) => void) => void
  ref?: Ref<ComposerHandle>
}) {
  const [text, setText] = useState('')
  const [picked, setPicked] = useState<PickedFile | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const textareaRef = useRef<HTMLTextAreaElement>(null)
  const inputRefs = useRef<Partial<Record<MediaKind, HTMLInputElement | null>>>({})

  // One TypingSender for this chat window (the page gives the window a key, so conversationId never changes).
  const [typingSender] = useState(
    () => new TypingSender((typing) => webSocketManager.publish('/app/chat.typing', { conversationId, typing })),
  )
  useEffect(() => {
    return () => typingSender.stop() // leaving the chat: say "stopped typing"
  }, [typingSender])

  // Voice notes. After 5 minutes the recorder stops by itself and hands the recording over.
  const recorder = useVoiceRecorder({ onAutoFinished: (result) => sendVoice(result) })

  // The temporary preview link of a picture is freed when it is replaced or the composer goes away.
  const imageUrlRef = useRef<string | null>(null)
  useEffect(() => {
    imageUrlRef.current = picked?.imageUrl ?? null
  })
  useEffect(() => {
    return () => {
      if (imageUrlRef.current) URL.revokeObjectURL(imageUrlRef.current)
    }
  }, [])

  function pickFile(file: File, kind: MediaKind) {
    const problem = validateFile(file, kind)
    if (problem) {
      setNotice(problem)
      return
    }
    setNotice(null)
    if (picked?.imageUrl) URL.revokeObjectURL(picked.imageUrl)
    setPicked({ file, kind, imageUrl: kind === 'IMAGE' ? URL.createObjectURL(file) : null })
    textareaRef.current?.focus()
  }

  useImperativeHandle(ref, () => ({ attachFile: (file) => pickFile(file, guessKind(file)) }))

  function removePicked() {
    if (picked?.imageUrl) URL.revokeObjectURL(picked.imageUrl)
    setPicked(null)
  }

  const canSend = picked !== null || text.trim().length > 0

  function handleChange(value: string) {
    setText(value)
    if (value.trim().length === 0) {
      typingSender.stop()
    } else {
      typingSender.keystroke()
    }
  }

  function send() {
    if (!canSend) return
    typingSender.stop()
    if (picked) {
      onSendAttachment?.(
        { file: picked.file, fileName: picked.file.name, kind: picked.kind, caption: text, durationSeconds: null },
        setNotice,
      )
      // The preview link is no longer needed here: the message bubble makes its own.
      removePicked()
    } else {
      onSend(text)
    }
    setText('')
    textareaRef.current?.focus()
  }

  function sendVoice(result: VoiceResult) {
    onSendAttachment?.(
      { file: result.blob, fileName: result.fileName, kind: 'VOICE', caption: '', durationSeconds: result.durationSeconds },
      setNotice,
    )
  }

  async function finishRecording() {
    const result = await recorder.finish()
    if (result) sendVoice(result)
  }

  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    // Enter sends. Shift+Enter (new line) and typing with an IME (composition) are left alone.
    if (event.key === 'Enter' && !event.shiftKey && !event.nativeEvent.isComposing) {
      event.preventDefault()
      send()
    }
  }

  // Pasting a picture (for example a screenshot) attaches it.
  function handlePaste(event: ClipboardEvent<HTMLTextAreaElement>) {
    const image = Array.from(event.clipboardData.files).find((file) => file.type.startsWith('image/'))
    if (image) {
      event.preventDefault()
      pickFile(image, 'IMAGE')
    }
  }

  const recording = recorder.status === 'recording'
  const problem = notice ?? recorder.error

  return (
    <div className="border-t border-gray-200/80 bg-white p-2.5 sm:px-5 sm:py-3 dark:border-gray-800 dark:bg-gray-900">
      {picked && (
        <AttachmentPreviewTray file={picked.file} kind={picked.kind} imageUrl={picked.imageUrl} onRemove={removePicked} />
      )}
      {problem && (
        <div role="alert" className="mb-2 flex items-start gap-2 rounded bg-red-50 px-3 py-2 text-sm text-red-700 dark:bg-red-950 dark:text-red-300">
          <span className="flex-1">{problem}</span>
          <button
            type="button"
            aria-label="Dismiss"
            onClick={() => {
              setNotice(null)
              recorder.clearError()
            }}
            className="rounded px-1 hover:bg-red-100 dark:hover:bg-red-900"
          >
            x
          </button>
        </div>
      )}

      {/* The real file pickers stay hidden; the menu opens them. */}
      {PICKER_KINDS.map((kind) => (
        <input
          key={kind}
          ref={(element) => {
            inputRefs.current[kind] = element
          }}
          type="file"
          hidden
          accept={acceptAttribute(kind)}
          aria-label={`Choose a ${MEDIA_RULES[kind].label}`}
          onChange={(event) => {
            const file = event.target.files?.[0]
            event.target.value = '' // so the same file can be picked again
            if (file) pickFile(file, kind)
          }}
        />
      ))}

      <div className="flex items-end gap-2">
        {recording ? (
          <VoiceRecorderBar seconds={recorder.seconds} onCancel={recorder.cancel} onSend={() => void finishRecording()} />
        ) : (
          <>
            <AttachMenu onPick={(kind) => inputRefs.current[kind]?.click()} />
            <textarea
              ref={textareaRef}
              value={text}
              onChange={(event) => handleChange(event.target.value)}
              onKeyDown={handleKeyDown}
              onPaste={handlePaste}
              maxLength={MAX_LENGTH}
              rows={1}
              placeholder={picked ? 'Add a caption' : 'Type a message'}
              aria-label="Message"
              className="max-h-32 min-h-10 flex-1 resize-none rounded-2xl border border-gray-200 bg-white px-4 py-2.5 shadow-sm outline-none transition focus:border-brand-500 focus:ring-4 focus:ring-brand-500/15 dark:border-gray-700 dark:bg-gray-800"
            />
            {!canSend && (
              <button
                type="button"
                onClick={() => void recorder.start()}
                disabled={recorder.status === 'starting'}
                aria-label="Record a voice note"
                className="rounded-full p-2.5 text-gray-500 hover:bg-gray-100 disabled:opacity-50 dark:text-gray-300 dark:hover:bg-gray-800"
              >
                <Mic size={20} />
              </button>
            )}
            <button
              type="button"
              onClick={send}
              disabled={!canSend}
              aria-label="Send message"
              className="btn-brand rounded-full p-2.5 text-white transition-transform hover:scale-105 disabled:cursor-not-allowed disabled:opacity-40 disabled:shadow-none disabled:hover:scale-100"
            >
              <Send size={20} />
            </button>
          </>
        )}
      </div>
      {text.length >= SHOW_COUNTER_FROM && (
        <p className="mt-1 text-right text-xs text-gray-500" aria-live="polite">
          {text.length} / {MAX_LENGTH}
        </p>
      )}
    </div>
  )
}
