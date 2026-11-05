import { AlertCircle, CheckCircle2, Info } from 'lucide-react'
import type { ReactNode } from 'react'

type AlertType = 'error' | 'success' | 'info'

const STYLES: Record<AlertType, string> = {
  error: 'border-red-200 bg-red-50 text-red-700 dark:border-red-900/60 dark:bg-red-950/60 dark:text-red-300',
  success: 'border-brand-200 bg-brand-50 text-brand-800 dark:border-brand-900/60 dark:bg-brand-950/60 dark:text-brand-200',
  info: 'border-sky-200 bg-sky-50 text-sky-800 dark:border-sky-900/60 dark:bg-sky-950/60 dark:text-sky-200',
}

// A small message box used for all success / error / info feedback.
export default function Alert({ type, children }: { type: AlertType; children: ReactNode }) {
  const Icon = type === 'error' ? AlertCircle : type === 'success' ? CheckCircle2 : Info
  return (
    <div role={type === 'error' ? 'alert' : 'status'} className={`flex animate-pop items-start gap-2 rounded-xl border p-3 text-sm ${STYLES[type]}`}>
      <Icon size={18} className="mt-0.5 shrink-0" />
      <div>{children}</div>
    </div>
  )
}
