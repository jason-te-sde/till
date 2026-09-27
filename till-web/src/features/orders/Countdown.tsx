import { useEffect, useState } from 'react'
import { remaining } from '../../format'

/**
 * How long is left on a hold.
 *
 * Driven from the deadline the store gave, not from a duration counted down locally: a tab backgrounded
 * for ten minutes has to come back showing the truth rather than resuming where it left off.
 *
 * The state is the current instant, and what is left is derived from it during render. The obvious
 * shape — "remaining" in state, reset in an effect when the deadline changes — writes state from an
 * effect, which is a cascading render and which React's own lint rules reject.
 */
export function Countdown({
  deadline,
  onElapsed,
  className = '',
}: {
  deadline: string
  onElapsed?: () => void
  className?: string
}) {
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
    // second for as long as the page stays open.
  }, [elapsed, onElapsed])

  const urgent = left < 60_000
  return (
    <span role="timer" aria-live="off" className={`numeric font-bold ${urgent ? 'text-warning-ink' : ''} ${className}`}>
      {remaining(left)}
    </span>
  )
}
