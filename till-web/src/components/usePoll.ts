import { useCallback, useEffect, useRef, useState, useSyncExternalStore } from 'react'

export interface Polled<T> {
  readonly data: T | undefined
  readonly error: unknown
  readonly loading: boolean
  readonly refresh: () => void
}

/**
 * Re-reads something on an interval, and on demand.
 *
 * Three things it does that a `useEffect` with a `setInterval` usually does not:
 *
 * - **It does not overlap itself.** A slow response does not queue a second request behind it, which
 *   is how a console pointed at a struggling service turns into a load test.
 * - **It keeps the last good value while refreshing.** A table that empties on every tick is a table
 *   nobody can read.
 * - **It drops the answer to a request it no longer wants**, so a response that arrives after the
 *   component unmounts, or after a newer request, cannot overwrite what is on screen.
 * - **It reads again the moment the query changes.** Waiting out the interval means a filter button
 *   that appears to do nothing for two seconds, which is long enough for somebody to click it twice.
 * - **It stops while the tab is hidden, and catches up the instant it comes back.** A console left
 *   open in a background tab overnight is otherwise thirty thousand requests nobody read the answer
 *   to — and the first thing an operator does on returning is look at the screen, so the stale
 *   numbers sitting there are the worst possible moment to be showing them.
 *
 * `read` must be memoised — a `useCallback` whose dependencies are the query. Its identity is the
 * signal that the question changed.
 */
export function usePoll<T>(
  read: () => Promise<T>,
  intervalMs: number,
  enabled = true,
): Polled<T> {
  const visible = useVisible()
  const [data, setData] = useState<T | undefined>(undefined)
  const [error, setError] = useState<unknown>(undefined)
  const [loading, setLoading] = useState(false)

  // Kept in a ref so that the interval always calls the newest reader without the interval being
  // torn down and restarted whenever a caller passes a new closure. Written in an effect rather
  // than during render: a ref write during render is a side effect, and React's lint rule says so.
  const readRef = useRef(read)
  useEffect(() => {
    readRef.current = read
  }, [read])

  const inFlight = useRef(false)
  const generation = useRef(0)
  const alive = useRef(true)

  const run = useCallback(() => {
    if (inFlight.current) {
      return
    }
    inFlight.current = true
    const mine = ++generation.current
    setLoading(true)
    readRef
      .current()
      .then((value) => {
        if (alive.current && mine === generation.current) {
          setData(value)
          setError(undefined)
        }
      })
      .catch((cause: unknown) => {
        if (alive.current && mine === generation.current) {
          setError(cause)
        }
      })
      .finally(() => {
        inFlight.current = false
        if (alive.current && mine === generation.current) {
          setLoading(false)
        }
      })
  }, [])

  useEffect(() => {
    alive.current = true
    return () => {
      alive.current = false
    }
  }, [])

  // On mount, again whenever the question changes, and again when the tab comes back — at which
  // point what is on screen is as old as the time spent away. Keeping this separate from the timer
  // means a filter change does not also restart the interval, and the interval does not also
  // re-read. A tab opened in the background reads when it is first looked at, which is the same
  // effect and one request cheaper.
  useEffect(() => {
    if (enabled && visible) {
      run()
    }
  }, [enabled, read, run, visible])

  useEffect(() => {
    if (!enabled || !visible) {
      return
    }
    const timer = setInterval(run, intervalMs)
    return () => {
      clearInterval(timer)
    }
  }, [enabled, intervalMs, run, visible])

  return { data, error, loading, refresh: run }
}

/**
 * Whether the tab is in front.
 *
 * `useSyncExternalStore` rather than state plus an effect, because that is exactly what this is: a
 * value that lives outside React and changes without React's knowledge. It also gets the
 * server-render case right for free, which matters only in that the alternative gets it wrong
 * silently.
 */
function useVisible(): boolean {
  return useSyncExternalStore(subscribeToVisibility, isVisible, alwaysVisible)
}

function subscribeToVisibility(onChange: () => void): () => void {
  document.addEventListener('visibilitychange', onChange)
  return () => {
    document.removeEventListener('visibilitychange', onChange)
  }
}

const isVisible = () => document.visibilityState !== 'hidden'

/** There is no tab to hide during a server render. */
const alwaysVisible = () => true
