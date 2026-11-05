import Avatar from '../common/Avatar'

// A round picture with a ring around it: one ring segment per status.
// Segments of statuses I have not seen yet are green; seen ones are grey (like WhatsApp).
export default function StatusRing({
  name,
  imageUrl,
  segments,
  size = 48,
}: {
  name: string
  imageUrl?: string | null
  // One entry per status, oldest first: true = highlight (not seen yet), false = grey.
  segments: boolean[]
  size?: number
}) {
  const stroke = 3
  const gap = 3 // space between two segments, measured along the ring
  const outer = size + 8 // the ring sits around the picture
  const radius = (outer - stroke) / 2
  const circumference = 2 * Math.PI * radius
  const count = Math.max(1, segments.length)
  const slot = circumference / count
  const length = count === 1 ? circumference : Math.max(1, slot - gap)

  return (
    <span className="relative inline-flex shrink-0 items-center justify-center" style={{ width: outer, height: outer }}>
      <svg
        width={outer}
        height={outer}
        className="absolute inset-0 -rotate-90"
        aria-hidden="true"
        data-testid="status-ring"
      >
        {(segments.length > 0 ? segments : [false]).map((highlight, index) => (
          <circle
            key={index}
            cx={outer / 2}
            cy={outer / 2}
            r={radius}
            fill="none"
            strokeWidth={stroke}
            strokeLinecap="butt"
            strokeDasharray={`${length} ${circumference}`}
            strokeDashoffset={-index * slot}
            data-highlight={highlight}
            className={highlight ? 'stroke-brand-500' : 'stroke-gray-300 dark:stroke-gray-600'}
          />
        ))}
      </svg>
      <Avatar name={name} imageUrl={imageUrl} size={size} />
    </span>
  )
}
