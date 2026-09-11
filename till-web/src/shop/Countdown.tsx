import { useEffect, useState } from 'react'
import { remaining as formatRemaining } from '../format'

/**
 * How long is left on a hold.
 *
 * Driven from the deadline the service gave us, not from a duration counted down locally — a tab
 * backgrounded for ten minutes has to come back showing the truth rather than resuming where it left
 * off.
 *
 * The state is the current instant, and what is left is derived from it during render. The obvious
 * shape — keep "remaining" in state and reset it in an effect when the deadline changes — writes
 * state synchronously from an effect, which is a cascading render and which React's own lint rule
 * now rejects. Deriving costs nothing and removes the effect entirely.
 */
export function Countdown({ deadline, onElapsed }: { deadline: string; onElapsed?: () => void }) {
  const target = new Date(deadline).getTime()
  const [now, setNow] = useState(() => Date.now())

  useEffect(() => {
    const timer = setInterval(() => {
      setNow(Date.now())
    }, 250)
    return () => {
      clearInterval(timer)
    }
  }, [])

  const left = target - now
  const elapsed = left <= 0

  useEffect(() => {
    if (elapsed) {
      onElapsed?.()
    }
    // Keyed on the crossing rather than on the instant, so this fires once rather than four times a
    // second for as long as the page is open.
  }, [elapsed, onElapsed])

  return (
    <span
      className="numeric font-semibold"
      style={{ color: elapsed ? 'var(--status-warning)' : undefined }}
      role="timer"
      aria-live="off"
    >
      {formatRemaining(left)}
    </span>
  )
}
