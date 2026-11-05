import { Loader2 } from 'lucide-react'
import type { ComponentProps } from 'react'

// The main button: brand gradient with a soft glow, shows a spinner and disables itself while `loading` is true.
export default function SubmitButton({
  loading = false,
  children,
  className = '',
  ...rest
}: ComponentProps<'button'> & { loading?: boolean }) {
  return (
    <button
      type="submit"
      {...rest}
      disabled={loading || rest.disabled}
      className={`btn-brand flex w-full items-center justify-center gap-2 rounded-xl px-4 py-2.5 font-semibold text-white disabled:cursor-not-allowed disabled:opacity-50 disabled:shadow-none ${className}`}
    >
      {loading && <Loader2 className="animate-spin" size={18} />}
      {children}
    </button>
  )
}
