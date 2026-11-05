import { MessageCircle } from 'lucide-react'

// The app's logo mark: a speech bubble on a gradient tile.
export default function Brand({ size = 40 }: { size?: number }) {
  return (
    <span
      aria-hidden="true"
      className="btn-brand inline-flex shrink-0 items-center justify-center text-white"
      style={{ width: size, height: size, borderRadius: size * 0.3 }}
    >
      <MessageCircle size={size * 0.55} strokeWidth={2.4} />
    </span>
  )
}
