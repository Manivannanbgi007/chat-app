import type { ReactNode } from 'react'

// The title block at the top of a page: big heading, a short line of help and (optionally) a button on the right.
export default function PageHeader({ title, subtitle, action }: { title: string; subtitle?: string; action?: ReactNode }) {
  return (
    <header className="mb-6 flex flex-wrap items-end justify-between gap-3">
      <div className="min-w-0">
        <h1 className="text-3xl font-bold tracking-tight">{title}</h1>
        {subtitle && <p className="mt-1 text-gray-500 dark:text-gray-400">{subtitle}</p>}
      </div>
      {action}
    </header>
  )
}
