import { useEffect, useState } from 'react'

// Returns `value`, but only after it has stopped changing for `delayMs`.
// Used for the search box so we do not call the server on every key press.
export function useDebouncedValue(value, delayMs) {
  const [debounced, setDebounced] = useState(value)

  useEffect(() => {
    const timer = setTimeout(() => setDebounced(value), delayMs)
    return () => clearTimeout(timer)
  }, [value, delayMs])

  return debounced
}
