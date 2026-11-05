import { X } from 'lucide-react'
import { useEffect, useRef } from 'react'

// A full-screen view of a picture. Esc, the X button or a click on the dark background close it, and the
// focus goes to the close button when it opens and back to where it was when it closes.
export default function ImageLightbox({ src, alt, onClose }: { src: string | null; alt: string; onClose: () => void }) {
  const closeRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    const previouslyFocused = document.activeElement as HTMLElement | null
    closeRef.current?.focus()
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('keydown', onKeyDown)
      previouslyFocused?.focus()
    }
  }, [onClose])

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label={`Photo: ${alt}`}
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/80 p-4"
      onClick={onClose}
    >
      <button
        ref={closeRef}
        type="button"
        onClick={onClose}
        aria-label="Close photo"
        className="absolute right-4 top-4 rounded-full bg-white/20 p-2 text-white hover:bg-white/30"
      >
        <X size={24} />
      </button>
      {src ? (
        <img
          src={src}
          alt={alt}
          className="max-h-full max-w-full object-contain"
          onClick={(event) => event.stopPropagation()}
        />
      ) : (
        <p className="text-white">Loading...</p>
      )}
    </div>
  )
}
