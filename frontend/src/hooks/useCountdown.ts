import { useEffect, useState } from 'react'

// Counts down from `initialSeconds` to 0, one step per second. Call start(n) to restart.
export function useCountdown(initialSeconds = 0) {
  const [secondsLeft, setSecondsLeft] = useState(initialSeconds)
  const running = secondsLeft > 0

  useEffect(() => {
    if (!running) return
    const timer = setInterval(() => setSecondsLeft((s) => Math.max(0, s - 1)), 1000)
    return () => clearInterval(timer)
  }, [running])

  return { secondsLeft, start: setSecondsLeft }
}
