import type { ReactNode } from 'react'

// A modern on / off switch. It is still a real checkbox underneath (keyboard, screen readers and tests work as before);
// the track and the knob are drawn with CSS. The label (and an optional hint) is part of the click area.
export default function Toggle({
  checked,
  onChange,
  disabled = false,
  children,
  hint,
}: {
  checked: boolean
  onChange: (checked: boolean) => void
  disabled?: boolean
  children: ReactNode
  hint?: ReactNode
}) {
  return (
    <label className={`flex items-start gap-3 text-sm ${disabled ? 'cursor-not-allowed opacity-60' : 'cursor-pointer'}`}>
      <span className="relative mt-0.5 inline-flex h-6 w-11 shrink-0">
        <input
          type="checkbox"
          className="peer sr-only"
          checked={checked}
          disabled={disabled}
          onChange={(event) => onChange(event.target.checked)}
        />
        <span className="absolute inset-0 rounded-full bg-gray-300 transition-colors duration-200 peer-checked:bg-brand-600 peer-focus-visible:ring-4 peer-focus-visible:ring-brand-500/25 dark:bg-gray-700" />
        <span className="absolute left-0.5 top-0.5 h-5 w-5 rounded-full bg-white shadow transition-transform duration-200 peer-checked:translate-x-5" />
      </span>
      <span className="min-w-0 flex-1">
        <span className="block font-medium">{children}</span>
        {hint && <span className="mt-0.5 block text-xs text-gray-500 dark:text-gray-400">{hint}</span>}
      </span>
    </label>
  )
}
