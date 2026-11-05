import type { ReactNode } from 'react'

// Tailwind classes shared by all text inputs.
export const inputClass =
  'w-full rounded-xl border border-gray-200 bg-white px-3.5 py-2.5 shadow-sm outline-none transition placeholder:text-gray-400 focus:border-brand-500 focus:ring-4 focus:ring-brand-500/15 dark:border-gray-700 dark:bg-gray-800'

// A label, an input (passed as children) and the validation message below it.
export default function FormField({
  label,
  htmlFor,
  error,
  hint,
  children,
}: {
  label: string
  htmlFor: string
  error?: string
  hint?: string
  children: ReactNode
}) {
  return (
    <div className="mb-4">
      <label htmlFor={htmlFor} className="mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-200">
        {label}
      </label>
      {children}
      {hint && !error && <p className="mt-1 text-xs text-gray-500 dark:text-gray-400">{hint}</p>}
      {error && (
        <p className="mt-1 animate-fade-in text-sm text-red-600" role="alert">
          {error}
        </p>
      )}
    </div>
  )
}
