// Colour pairs for the picture of somebody without a photo. The pair is chosen from the name, so one person always
// has the same colours everywhere in the app.
const GRADIENTS = [
  'from-violet-500 to-indigo-600',
  'from-sky-500 to-blue-600',
  'from-emerald-500 to-teal-600',
  'from-amber-500 to-orange-600',
  'from-rose-500 to-pink-600',
  'from-fuchsia-500 to-purple-600',
  'from-cyan-500 to-sky-600',
  'from-lime-500 to-green-600',
]

function gradientFor(name: string): string {
  let hash = 0
  for (const char of name) hash = (hash * 31 + char.charCodeAt(0)) >>> 0
  return GRADIENTS[hash % GRADIENTS.length]
}

// Round picture of a user. Without a photo it shows the initials on a colour that belongs to the name.
export default function Avatar({
  name,
  imageUrl,
  size = 40,
}: {
  name: string
  imageUrl?: string | null
  size?: number
}) {
  const initials = name
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map((word) => word[0]?.toUpperCase() ?? '')
    .join('')

  if (imageUrl) {
    return <img src={imageUrl} alt={name} width={size} height={size} className="shrink-0 rounded-full object-cover ring-2 ring-white dark:ring-gray-900" style={{ width: size, height: size }} />
  }

  return (
    <span
      aria-hidden="true"
      className={`inline-flex shrink-0 select-none items-center justify-center rounded-full bg-gradient-to-br font-semibold text-white shadow-sm ring-2 ring-white dark:ring-gray-900 ${gradientFor(name)}`}
      style={{ width: size, height: size, fontSize: size / 2.6 }}
    >
      {initials || '?'}
    </span>
  )
}
