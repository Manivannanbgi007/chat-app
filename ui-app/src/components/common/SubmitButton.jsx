import { Loader2 } from 'lucide-react'

// The main button: brand gradient with a soft glow, shows a spinner and disables itself while `loading` is true.
// Accepts every <button> prop as well.
export default function SubmitButton({ loading = false, children, className = '', ...rest }) {
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
