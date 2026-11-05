// "just now", "5 min ago", "3 h ago", "yesterday", "02 Nov". Pure function (relativeTime.test.ts).

const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec']

export function relativeTime(iso: string, now: Date = new Date()): string {
  const then = new Date(iso)
  if (Number.isNaN(then.getTime())) return ''
  const seconds = Math.floor((now.getTime() - then.getTime()) / 1000)

  if (seconds < 60) return 'just now' // also covers small clock differences (negative values)
  const minutes = Math.floor(seconds / 60)
  if (minutes < 60) return `${minutes} min ago`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} h ago`

  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime()
  const startOfThen = new Date(then.getFullYear(), then.getMonth(), then.getDate()).getTime()
  const daysAgo = Math.round((startOfToday - startOfThen) / 86_400_000)
  if (daysAgo <= 1) return 'yesterday'
  const day = String(then.getDate()).padStart(2, '0')
  return `${day} ${MONTHS[then.getMonth()]}`
}
