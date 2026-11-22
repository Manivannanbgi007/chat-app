import { Eye, EyeOff } from 'lucide-react'
import { useState } from 'react'
import { inputClass } from './FormField'

// A password input with a show/hide button. Accepts the same props as <input> (and useForm's register()).
export default function PasswordInput(props) {
  const [visible, setVisible] = useState(false)

  return (
    <div className="relative">
      <input {...props} type={visible ? 'text' : 'password'} className={`${inputClass} pr-10`} />
      <button
        type="button"
        onClick={() => setVisible((v) => !v)}
        aria-label={visible ? 'Hide password' : 'Show password'}
        className="absolute inset-y-0 right-0 px-3 text-gray-500 hover:text-gray-800 dark:hover:text-gray-200"
      >
        {visible ? <EyeOff size={18} /> : <Eye size={18} />}
      </button>
    </div>
  )
}
