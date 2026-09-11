import { act, renderHook, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { TillClient } from '../src/api/client'
import { add, EMPTY } from '../src/shop/cart'
import { useCheckout } from '../src/shop/useCheckout'
import { BASE, problem } from './fixtures'
import { server } from './server'

const CART = add(add(EMPTY, 'widget', 9), 'widget', 9)

const RESERVED = {
  id: 'r-1',
  lines: [{ sku: 'widget', quantity: 2 }],
  expiresAt: '2026-09-11T12:02:00Z',
}

function checkout() {
  const client = new TillClient({
    baseUrl: BASE,
    token: 'secret',
    attempts: 1,
    sleep: () => Promise.resolve(),
  })
  return renderHook(() => useCheckout(client))
}

/**
 * The checkout state machine.
 *
 * Every test here is about a key: which requests share one, which get a new one, and what the page
 * ends up believing when something goes wrong. Those are the properties that decide whether a
 * customer is charged once or twice, and none of them is visible from a screenshot.
 */
describe('checking out', () => {
  it('takes a hold, then turns it into a sale', async () => {
    server.use(
      http.post(`${BASE}/v1/reservations`, () => HttpResponse.json(RESERVED, { status: 201 })),
      http.post(`${BASE}/v1/reservations/r-1/commit`, () =>
        HttpResponse.json({ id: 'r-1', committedAt: '2026-09-11T12:00:30Z' }),
      ),
    )
    const { result } = checkout()

    await act(async () => {
      await result.current.start(CART)
    })
    expect(result.current.phase.kind).toBe('held')

    await act(async () => {
      await result.current.pay()
    })
    expect(result.current.phase).toMatchObject({ kind: 'paid', at: '2026-09-11T12:00:30Z' })
  })

  it('gives every step of one attempt a key derived from the attempt', async () => {
    const keys: (string | null)[] = []
    server.use(
      http.post(`${BASE}/v1/reservations`, ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key'))
        return HttpResponse.json(RESERVED, { status: 201 })
      }),
      http.post(`${BASE}/v1/reservations/r-1/commit`, ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key'))
        return HttpResponse.json({ id: 'r-1', committedAt: '2026-09-11T12:00:30Z' })
      }),
    )
    const { result } = checkout()

    await act(async () => {
      await result.current.start(CART)
    })
    const attempt = result.current.attemptKey!
    await act(async () => {
      await result.current.pay()
    })

    expect(keys[0]).toBe(attempt)
    expect(keys[1]).toBe(`${attempt}.pay`)
  })

  it('sends one key when Pay is clicked twice, so there is one sale', async () => {
    const keys: (string | null)[] = []
    server.use(
      http.post(`${BASE}/v1/reservations`, () => HttpResponse.json(RESERVED, { status: 201 })),
      http.post(`${BASE}/v1/reservations/r-1/commit`, ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key'))
        return HttpResponse.json({ id: 'r-1', committedAt: '2026-09-11T12:00:30Z' })
      }),
    )
    const { result } = checkout()
    await act(async () => {
      await result.current.start(CART)
    })

    await act(async () => {
      await Promise.all([result.current.pay(), result.current.pay()])
    })

    // The second click may not reach the network at all — the phase has already moved on — and if
    // it does it carries the same key. Either way the service executes once.
    expect(new Set(keys).size).toBeLessThanOrEqual(1)
    expect(result.current.phase.kind).toBe('paid')
  })

  it('reuses the attempt key when the basket is tried again', async () => {
    const keys: (string | null)[] = []
    let calls = 0
    server.use(
      http.post(`${BASE}/v1/reservations`, ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key'))
        calls++
        return calls === 1
          ? problem(409, 'INSUFFICIENT_STOCK', 'not enough stock for widget', [
              { sku: 'widget', requested: 2, available: 0 },
            ])
          : HttpResponse.json(RESERVED, { status: 201 })
      }),
    )
    const { result } = checkout()

    await act(async () => {
      await result.current.start(CART)
    })
    expect(result.current.phase.kind).toBe('refused')

    await act(async () => {
      await result.current.retry(CART)
    })

    // Deliberately the same key: this is the same attempt at the same basket, and the service
    // answering it from the record is the correct outcome.
    expect(keys[0]).toBe(keys[1])
    expect(result.current.phase.kind).toBe('held')
  })

  it('reports a refusal rather than pretending the hold exists', async () => {
    server.use(
      http.post(`${BASE}/v1/reservations`, () =>
        problem(409, 'INSUFFICIENT_STOCK', 'not enough stock for widget', [
          { sku: 'widget', requested: 2, available: 1 },
        ]),
      ),
    )
    const { result } = checkout()

    await act(async () => {
      await result.current.start(CART)
    })

    expect(result.current.phase).toMatchObject({ kind: 'refused' })
  })

  it('treats a commit refused for expiry as the hold being gone, not as an error', async () => {
    server.use(
      http.post(`${BASE}/v1/reservations`, () => HttpResponse.json(RESERVED, { status: 201 })),
      http.post(`${BASE}/v1/reservations/r-1/commit`, () =>
        problem(410, 'RESERVATION_EXPIRED', 'reservation r-1 expired'),
      ),
    )
    const { result } = checkout()
    await act(async () => {
      await result.current.start(CART)
    })

    await act(async () => {
      await result.current.pay()
    })

    // The customer needs "that ran out, the stock is back", not a stack of jargon.
    expect(result.current.phase).toMatchObject({ kind: 'gone', why: 'expired' })
  })

  it('cancels the hold, and does not tell anybody when the cancel itself fails', async () => {
    server.use(
      http.post(`${BASE}/v1/reservations`, () => HttpResponse.json(RESERVED, { status: 201 })),
      http.post(`${BASE}/v1/reservations/r-1/release`, () => HttpResponse.error()),
    )
    const { result } = checkout()
    await act(async () => {
      await result.current.start(CART)
    })

    await act(async () => {
      await result.current.cancel()
    })

    // The hold expires on its own regardless, so a failed release changes nothing a customer sees.
    expect(result.current.phase).toMatchObject({ kind: 'gone', why: 'cancelled' })
  })

  it('lets the deadline end the hold without a request', async () => {
    server.use(http.post(`${BASE}/v1/reservations`, () => HttpResponse.json(RESERVED, { status: 201 })))
    const { result } = checkout()
    await act(async () => {
      await result.current.start(CART)
    })

    act(() => {
      result.current.expire()
    })

    expect(result.current.phase).toMatchObject({ kind: 'gone', why: 'expired' })
  })

  it('ignores a pay or cancel that arrives after the phase has moved on', async () => {
    server.use(http.post(`${BASE}/v1/reservations`, () => HttpResponse.json(RESERVED, { status: 201 })))
    const { result } = checkout()
    await act(async () => {
      await result.current.start(CART)
    })
    act(() => {
      result.current.expire()
    })

    await act(async () => {
      await result.current.pay()
      await result.current.cancel()
    })

    // No handler is registered for commit or release. If either had been sent, MSW would fail the
    // test on an unhandled request, which is the assertion.
    expect(result.current.phase).toMatchObject({ kind: 'gone', why: 'expired' })
  })

  it('starts a new attempt with a new key, so a second order is possible', async () => {
    const keys: (string | null)[] = []
    server.use(
      http.post(`${BASE}/v1/reservations`, ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key'))
        return HttpResponse.json(RESERVED, { status: 201 })
      }),
    )
    const { result } = checkout()

    await act(async () => {
      await result.current.start(CART)
    })
    act(() => {
      result.current.reset()
    })
    await act(async () => {
      await result.current.start(CART)
    })

    expect(result.current.phase.kind).toBe('held')
    expect(keys[0]).not.toBe(keys[1])
  })

  it('is shopping until something starts', async () => {
    const { result } = checkout()

    expect(result.current.phase.kind).toBe('shopping')
    expect(result.current.attemptKey).toBeNull()
    await waitFor(() => {
      expect(result.current.phase.kind).toBe('shopping')
    })
  })
})
