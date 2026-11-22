import { Check, CheckCheck, CircleAlert, Clock } from 'lucide-react'

// The little icon next to the time of a message I sent:
// clock = sending, one tick = saved on the server, two grey ticks = delivered, two blue ticks = read, red = failed.
// status: 'SENDING' | 'FAILED' | 'SENT' | 'DELIVERED' | 'READ' (DisplayStatus from features/messaging/messageMerge)
export default function StatusTicks({ status }) {
  const common = { size: 14, role: 'img' }
  switch (status) {
    case 'SENDING':
      return <Clock {...common} aria-label="Sending" />
    case 'FAILED':
      return <CircleAlert {...common} aria-label="Failed to send" className="text-red-500" />
    case 'SENT':
      return <Check {...common} aria-label="Sent" />
    case 'DELIVERED':
      return <CheckCheck {...common} aria-label="Delivered" />
    case 'READ':
      return <CheckCheck {...common} aria-label="Read" className="text-sky-500" />
    default:
      return null
  }
}
