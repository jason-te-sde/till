import { act, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Countdown } from '../src/shop/Countdown'

const NOW = new Date('2026-09-11T12:00:00Z')

/**
 * Advances the clock and lets React flush what the interval changed.
 *
 * `vi.advanceTimersByTime` on its own runs the callback but leaves the state update queued, so the
 * DOM still shows the previous second. Wrapping it is not a formality; without it these tests assert
 * on a frame that never renders in a browser.
 */
function tick(ms: number) {
  act(() => {
    vi.advanceTimersByTime(ms)
  })
}

describe('the countdown', () => {
  beforeEach(() => {
    vi.useFakeTimers({ now: NOW })
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('counts down from the deadline the service gave, not from a local duration', () => {
    render(<Countdown deadline="2026-09-11T12:02:00Z" />)

    expect(screen.getByRole('timer')).toHaveTextContent('2:00')

    tick(61_000)
    expect(screen.getByRole('timer')).toHaveTextContent('0:59')
  })

  it('shows the truth after the tab was asleep, rather than resuming where it stopped', () => {
    render(<Countdown deadline="2026-09-11T12:02:00Z" />)

    // A backgrounded tab does not get its intervals; when it wakes, the clock has moved.
    vi.setSystemTime(new Date('2026-09-11T12:01:30Z'))
    tick(250)

    expect(screen.getByRole('timer')).toHaveTextContent('0:30')
  })

  it('fires once when it crosses zero, not once per tick afterwards', () => {
    const onElapsed = vi.fn()
    render(<Countdown deadline="2026-09-11T12:00:02Z" onElapsed={onElapsed} />)

    expect(onElapsed).not.toHaveBeenCalled()

    tick(5000)

    expect(onElapsed).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('timer')).toHaveTextContent('0:00')
  })

  it('reports a deadline already in the past as gone', () => {
    const onElapsed = vi.fn()
    render(<Countdown deadline="2026-09-11T11:59:00Z" onElapsed={onElapsed} />)

    expect(screen.getByRole('timer')).toHaveTextContent('0:00')
    expect(onElapsed).toHaveBeenCalledTimes(1)
  })
})
