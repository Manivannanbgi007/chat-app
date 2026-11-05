import { Clock, Search, Smile } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'
import { EMOJI_GROUPS, searchEmoji, type EmojiItem } from '../../features/emoji/emojiData'
import { readRecentEmoji, rememberEmoji } from '../../features/emoji/emojiText'

// The smiley button next to the paperclip: opens a panel above it with a search box, tabs for the groups (the first tab
// shows the emoji used most recently) and a grid of emoji. A click on an emoji hands it to the composer, which puts it
// where the cursor is; the panel stays open so several can be added. Closes on Escape, on a click outside and on the button.
export default function EmojiButton({ onPick, onClose, disabled }: { onPick: (emoji: string) => void; onClose?: () => void; disabled?: boolean }) {
  const [open, setOpen] = useState(false)
  const rootRef = useRef<HTMLDivElement>(null)

  function close(giveFocusBack: boolean) {
    setOpen(false)
    if (giveFocusBack) onClose?.()
  }

  useEffect(() => {
    if (!open) return
    function onPointerDown(event: MouseEvent) {
      if (rootRef.current && !rootRef.current.contains(event.target as Node)) setOpen(false)
    }
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') close(true)
    }
    document.addEventListener('mousedown', onPointerDown)
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('mousedown', onPointerDown)
      document.removeEventListener('keydown', onKeyDown)
    }
    // close() only uses setOpen and onClose
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  return (
    <div ref={rootRef} className="relative">
      <button
        type="button"
        aria-label="Add emoji"
        aria-haspopup="dialog"
        aria-expanded={open}
        disabled={disabled}
        onClick={() => (open ? close(true) : setOpen(true))}
        className={`rounded-full p-2.5 transition-colors disabled:cursor-not-allowed disabled:opacity-50 ${
          open
            ? 'bg-brand-50 text-brand-600 dark:bg-brand-950/60 dark:text-brand-300'
            : 'text-gray-500 hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-gray-800'
        }`}
      >
        <Smile size={20} />
      </button>
      {open && <EmojiPanel onPick={onPick} />}
    </div>
  )
}

const RECENT_TAB = 'recent'

function EmojiPanel({ onPick }: { onPick: (emoji: string) => void }) {
  const [query, setQuery] = useState('')
  const [recent, setRecent] = useState<string[]>(readRecentEmoji)
  const [tab, setTab] = useState<string>(() => (readRecentEmoji().length > 0 ? RECENT_TAB : EMOJI_GROUPS[0].id))

  function pick(emoji: string) {
    setRecent(rememberEmoji(emoji))
    onPick(emoji)
  }

  const searching = query.trim().length > 0
  const results = searching ? searchEmoji(query) : []
  const group = EMOJI_GROUPS.find((item) => item.id === tab)
  const items: EmojiItem[] = searching
    ? results
    : tab === RECENT_TAB
      ? recent.map((emoji) => ({ emoji, name: 'recently used' }))
      : (group?.items ?? [])
  const title = searching ? `Results for "${query.trim()}"` : tab === RECENT_TAB ? 'Recently used' : (group?.label ?? '')

  return (
    <div
      role="dialog"
      aria-label="Emoji"
      className="absolute bottom-full left-0 z-30 mb-2 flex h-[22rem] w-[21rem] max-w-[calc(100vw-1.5rem)] animate-pop flex-col overflow-hidden rounded-2xl border border-gray-200/80 bg-white shadow-float dark:border-gray-800 dark:bg-gray-900"
    >
      <div className="relative p-2 pb-1">
        <Search size={15} aria-hidden="true" className="pointer-events-none absolute left-5 top-1/2 -translate-y-1/2 text-gray-400" />
        <input
          autoFocus
          type="search"
          value={query}
          onChange={(event) => setQuery(event.target.value)}
          onKeyDown={(event) => {
            // Enter picks the first result
            if (event.key === 'Enter' && searching && results.length > 0) {
              event.preventDefault()
              pick(results[0].emoji)
            }
          }}
          placeholder="Search emoji"
          aria-label="Search emoji"
          className="w-full rounded-xl border border-gray-200 bg-gray-50 py-2 pl-9 pr-3 text-sm outline-none transition focus:border-brand-500 focus:bg-white focus:ring-4 focus:ring-brand-500/15 dark:border-gray-700 dark:bg-gray-800 dark:focus:bg-gray-800"
        />
      </div>

      {!searching && (
        <div role="tablist" aria-label="Emoji groups" className="flex gap-0.5 border-b border-gray-100 px-2 pb-1.5 dark:border-gray-800">
          {recent.length > 0 && (
            <TabButton label="Recently used" active={tab === RECENT_TAB} onClick={() => setTab(RECENT_TAB)}>
              <Clock size={17} aria-hidden="true" />
            </TabButton>
          )}
          {EMOJI_GROUPS.map((item) => (
            <TabButton key={item.id} label={item.label} active={tab === item.id} onClick={() => setTab(item.id)}>
              <span aria-hidden="true" className="text-lg leading-none">
                {item.icon}
              </span>
            </TabButton>
          ))}
        </div>
      )}

      <p className="px-3 pb-1 pt-2 text-[11px] font-semibold uppercase tracking-wider text-gray-400">{title}</p>
      <div className="min-h-0 flex-1 overflow-y-auto px-2 pb-2">
        {items.length === 0 ? (
          <p role="status" className="px-2 py-8 text-center text-sm text-gray-500 dark:text-gray-400">
            {searching ? 'No emoji found' : 'Nothing here yet'}
          </p>
        ) : (
          <ul role="list" aria-label={title} className="grid grid-cols-8 gap-0.5">
            {items.map((item) => (
              <li key={item.emoji}>
                <button
                  type="button"
                  onClick={() => pick(item.emoji)}
                  aria-label={item.name}
                  title={item.name}
                  className="flex h-9 w-full items-center justify-center rounded-lg text-[1.4rem] leading-none transition hover:scale-125 hover:bg-gray-100 active:scale-100 dark:hover:bg-gray-800"
                >
                  {item.emoji}
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}

function TabButton({ label, active, onClick, children }: { label: string; active: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      type="button"
      role="tab"
      aria-selected={active}
      aria-label={label}
      title={label}
      onClick={onClick}
      className={`flex h-8 flex-1 items-center justify-center rounded-lg transition-colors ${
        active ? 'bg-brand-50 text-brand-600 dark:bg-brand-950/60 dark:text-brand-300' : 'text-gray-500 hover:bg-gray-100 dark:text-gray-400 dark:hover:bg-gray-800'
      }`}
    >
      {children}
    </button>
  )
}
