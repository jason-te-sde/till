import { act, renderHook, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { usePoll } from '../src/components/usePoll'

/**
 * Real timers and a very short interval, rather than fake ones.
 *
 * `waitFor` schedules its own timers, so combining it with a faked clock means the assertion and the
 * thing it is waiting for are both blocked on the same manual advance. A 10ms interval and a 60ms
 * wait is deterministic enough for "did this run once or six times" and needs none of that.
 */
const INTERVAL = 10

const sleep = (ms: number) =>
  new Promise<void>((resolve) => {
    setTimeout(resolve, ms)
  })

/** A promise that only resolves when the test says so. */
function pending<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((settle) => {
    resolve = settle
  })
  return { promise, resolve }
}

describe('polling', () => {
  it('reads once immediately rather than waiting out the first interval', async () => {
    const read = vi.fn<() => Promise<string>>().mockResolvedValue('first')

    const { result, unmount } = renderHook(() => usePoll(read, 60_000))

    await waitFor(() => {
      expect(result.current.data).toBe('first')
    })
    expect(read).toHaveBeenCalledTimes(1)
    unmount()
  })

  it('does not queue a second request behind a slow one', async () => {
    const slow = pending<string>()
    const read = vi.fn<() => Promise<string>>().mockReturnValue(slow.promise)

    const { unmount } = renderHook(() => usePoll(read, INTERVAL))
    // Several intervals pass with the first response still outstanding. A console that queued them
    // would turn a struggling service into a load test.
    await sleep(INTERVAL * 6)

    expect(read).toHaveBeenCalledTimes(1)
    slow.resolve('done')
    unmount()
  })

  it('reads again once the slow one comes back', async () => {
    const slow = pending<string>()
    const read = vi
      .fn<() => Promise<string>>()
      .mockReturnValueOnce(slow.promise)
      .mockResolvedValue('second')

    const { result, unmount } = renderHook(() => usePoll(read, INTERVAL))
    await sleep(INTERVAL * 3)
    slow.resolve('first')

    await waitFor(() => {
      expect(result.current.data).toBe('second')
    })
    unmount()
  })

  it('keeps the last good value while a refresh is in flight', async () => {
    const slow = pending<string>()
    const read = vi
      .fn<() => Promise<string>>()
      .mockResolvedValueOnce('first')
      .mockReturnValue(slow.promise)
    const { result, unmount } = renderHook(() => usePoll(read, INTERVAL))
    await waitFor(() => {
      expect(result.current.data).toBe('first')
    })

    await sleep(INTERVAL * 3)

    // A table that empties on every tick is a table nobody can read.
    expect(result.current.data).toBe('first')
    slow.resolve('later')
    unmount()
  })

  it('reports a failure without throwing away what is on screen', async () => {
    const read = vi
      .fn<() => Promise<string>>()
      .mockResolvedValueOnce('first')
      .mockRejectedValue(new Error('gone'))
    const { result, unmount } = renderHook(() => usePoll(read, INTERVAL))
    await waitFor(() => {
      expect(result.current.data).toBe('first')
    })

    await waitFor(() => {
      expect(result.current.error).toBeInstanceOf(Error)
    })
    expect(result.current.data).toBe('first')
    unmount()
  })

  it('clears the error once a read succeeds again', async () => {
    const read = vi
      .fn<() => Promise<string>>()
      .mockRejectedValueOnce(new Error('gone'))
      .mockResolvedValue('back')
    // A long interval and an explicit refresh, so the failure is observable: with a 10ms interval
    // the successful read lands before waitFor's first poll and the error is gone before it looks.
    const { result, unmount } = renderHook(() => usePoll(read, 60_000))
    await waitFor(() => {
      expect(result.current.error).toBeInstanceOf(Error)
    })

    result.current.refresh()

    await waitFor(() => {
      expect(result.current.error).toBeUndefined()
    })
    expect(result.current.data).toBe('back')
    unmount()
  })

  it('does not read at all when disabled', async () => {
    const read = vi.fn<() => Promise<string>>().mockResolvedValue('x')

    const { unmount } = renderHook(() => usePoll(read, INTERVAL, false))
    await sleep(INTERVAL * 5)

    expect(read).not.toHaveBeenCalled()
    unmount()
  })

  it('drops the answer to a request it no longer wants', async () => {
    const slow = pending<string>()
    const read = vi.fn<() => Promise<string>>().mockReturnValue(slow.promise)
    const { result, unmount } = renderHook(() => usePoll(read, INTERVAL))
    unmount()

    slow.resolve('too late')
    await sleep(INTERVAL * 2)

    // Writing state after unmount is the classic React warning; the reason it matters here is that
    // the same guard stops a slow response overwriting a newer one.
    expect(result.current.data).toBeUndefined()
  })

  it('reads again the moment the question changes', async () => {
    const read = vi.fn<() => Promise<string>>().mockResolvedValue('x')
    const { rerender, unmount } = renderHook(
      ({ reader }: { reader: () => Promise<string> }) => usePoll(reader, 60_000),
      { initialProps: { reader: read } },
    )
    await waitFor(() => {
      expect(read).toHaveBeenCalledTimes(1)
    })

    const other = vi.fn<() => Promise<string>>().mockResolvedValue('y')
    rerender({ reader: other })

    // A filter button that appears to do nothing for two seconds is a filter button people click
    // twice.
    await waitFor(() => {
      expect(other).toHaveBeenCalledTimes(1)
    })
    unmount()
  })

  it('refreshes on demand', async () => {
    const read = vi.fn<() => Promise<string>>().mockResolvedValue('first')
    const { result, unmount } = renderHook(() => usePoll(read, 60_000))
    await waitFor(() => {
      expect(result.current.data).toBe('first')
    })

    result.current.refresh()

    await waitFor(() => {
      expect(read).toHaveBeenCalledTimes(2)
    })
    unmount()
  })
})

/**
 * jsdom reports `visible` and has no way to change it, so the property is redefined and the event
 * dispatched by hand — which is exactly what the browser does, in the same order.
 */
function setVisibility(state: DocumentVisibilityState) {
  Object.defineProperty(document, 'visibilityState', {
    configurable: true,
    get: () => state,
  })
  document.dispatchEvent(new Event('visibilitychange'))
}

describe('polling a tab nobody is looking at', () => {
  afterEach(() => {
    setVisibility('visible')
  })

  it('stops while the tab is hidden', async () => {
    const read = vi.fn<() => Promise<string>>().mockResolvedValue('x')
    const { unmount } = renderHook(() => usePoll(read, INTERVAL))
    await waitFor(() => {
      expect(read).toHaveBeenCalledTimes(1)
    })

    act(() => {
      setVisibility('hidden')
    })
    const whileHidden = read.mock.calls.length
    await sleep(INTERVAL * 6)

    // Six intervals, no requests. A console left open overnight is otherwise thirty thousand of
    // them, and nobody read a single answer.
    expect(read).toHaveBeenCalledTimes(whileHidden)
    unmount()
  })

  it('catches up the moment the tab comes back', async () => {
    const read = vi.fn<() => Promise<string>>().mockResolvedValue('x')
    const { unmount } = renderHook(() => usePoll(read, 60_000))
    await waitFor(() => {
      expect(read).toHaveBeenCalledTimes(1)
    })
    act(() => {
      setVisibility('hidden')
    })

    act(() => {
      setVisibility('visible')
    })

    // Not on the next interval — now. The first thing somebody does on returning is read the
    // numbers, so that is the worst moment to be showing them the old ones.
    await waitFor(() => {
      expect(read).toHaveBeenCalledTimes(2)
    })
    unmount()
  })
})
