import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { TillClient, TillError, TillUnreachableError } from '../src/api/client'
import { BASE, problem, stock } from './fixtures'
import { server } from './server'

const KEY = 'checkout-8123'
const RESERVED = {
  id: 'r-1',
  lines: [{ sku: 'widget', quantity: 2 }],
  expiresAt: '2026-09-11T12:02:00Z',
}

/** No waiting, and the schedule becomes something a test can count. */
function client(attempts = 4) {
  return new TillClient({ baseUrl: BASE, token: 'secret', attempts, sleep: () => Promise.resolve() })
}

describe('the browser client', () => {
  it('sends the token, the key and the body', async () => {
    const seen: Request[] = []
    server.use(
      http.post(`${BASE}/v1/reservations`, ({ request }) => {
        seen.push(request.clone())
        return HttpResponse.json(RESERVED, { status: 201 })
      }),
    )

    await client().reserve(KEY, [{ sku: 'widget', quantity: 2 }], 120)

    const request = seen[0]!
    expect(request.headers.get('Authorization')).toBe('Bearer secret')
    expect(request.headers.get('Idempotency-Key')).toBe(KEY)
    expect(await request.json()).toEqual({
      lines: [{ sku: 'widget', quantity: 2 }],
      ttlSeconds: 120,
    })
  })

  it('retries a 503 with the same key, which is what makes retrying safe', async () => {
    const keys: (string | null)[] = []
    let calls = 0
    server.use(
      http.post(`${BASE}/v1/reservations`, ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key'))
        calls++
        return calls < 3
          ? problem(503, 'CONTENTION', 'try again')
          : HttpResponse.json(RESERVED, { status: 201 })
      }),
    )

    const reserved = await client().reserve(KEY, [{ sku: 'widget', quantity: 2 }])

    expect(reserved.id).toBe('r-1')
    expect(keys).toEqual([KEY, KEY, KEY])
  })

  it('retries a dropped connection, which is the case nobody can tell apart', async () => {
    const keys: (string | null)[] = []
    let calls = 0
    server.use(
      http.post(`${BASE}/v1/reservations`, ({ request }) => {
        keys.push(request.headers.get('Idempotency-Key'))
        calls++
        if (calls === 1) {
          // The request may have arrived, been applied, and had its answer lost.
          return HttpResponse.error()
        }
        return HttpResponse.json(RESERVED, { status: 201 })
      }),
    )

    await client().reserve(KEY, [{ sku: 'widget', quantity: 2 }])

    expect(keys).toEqual([KEY, KEY])
  })

  it('does not retry a refusal, because the answer will not change', async () => {
    let calls = 0
    server.use(
      http.post(`${BASE}/v1/reservations/r-1/commit`, () => {
        calls++
        return problem(409, 'ALREADY_COMMITTED', 'reservation r-1 was already committed')
      }),
    )

    await expect(client().commit(KEY, 'r-1')).rejects.toBeInstanceOf(TillError)
    expect(calls).toBe(1)
  })

  it('reports a refusal with its code and every shortfall', async () => {
    server.use(
      http.post(`${BASE}/v1/reservations`, () =>
        problem(409, 'INSUFFICIENT_STOCK', 'not enough stock for widget', [
          { sku: 'widget', requested: 5, available: 2 },
          { sku: 'gadget', requested: 1, available: 0 },
        ]),
      ),
    )

    const error = await client()
      .reserve(KEY, [{ sku: 'widget', quantity: 5 }])
      .catch((cause: unknown) => cause)

    expect(error).toBeInstanceOf(TillError)
    const refusal = error as TillError
    expect(refusal.status).toBe(409)
    expect(refusal.isOutOfStock).toBe(true)
    expect(refusal.shortfalls).toHaveLength(2)
    expect(refusal.shortfalls[1]).toEqual({ sku: 'gadget', requested: 1, available: 0 })
  })

  it('survives an error body that is not a problem detail', async () => {
    server.use(
      http.get(`${BASE}/v1/stock/widget`, () =>
        HttpResponse.text('<html>bad gateway</html>', { status: 502 }),
      ),
    )

    const error = await client()
      .getStock('widget')
      .catch((cause: unknown) => cause)

    expect((error as TillError).status).toBe(502)
    expect((error as TillError).code).toBeUndefined()
  })

  it('ignores a malformed shortfall rather than rendering undefined at somebody', async () => {
    server.use(
      http.post(`${BASE}/v1/reservations`, () =>
        HttpResponse.json(
          {
            code: 'INSUFFICIENT_STOCK',
            detail: 'no',
            shortfalls: [{ sku: 'widget' }, { sku: 'gadget', requested: 1, available: 0 }],
          },
          { status: 409 },
        ),
      ),
    )

    const error = (await client()
      .reserve(KEY, [{ sku: 'widget', quantity: 5 }])
      .catch((cause: unknown) => cause)) as TillError

    expect(error.shortfalls).toEqual([{ sku: 'gadget', requested: 1, available: 0 }])
  })

  it('gives up on a service that never answers, and says which case it was', async () => {
    server.use(http.get(`${BASE}/v1/stock/widget`, () => HttpResponse.error()))

    await expect(client(2).getStock('widget')).rejects.toBeInstanceOf(TillUnreachableError)
  })

  it('reports a 503 that outlasts every attempt as what the service said', async () => {
    server.use(http.get(`${BASE}/v1/stock/widget`, () => problem(503, 'CONTENTION', 'busy')))

    // Not an I/O failure: the service answered every time, and the caller should be told what it
    // answered rather than that the request never landed.
    const error = await client(2)
      .getStock('widget')
      .catch((cause: unknown) => cause)
    expect((error as TillError).status).toBe(503)
  })

  it('encodes a SKU that would otherwise change the path', async () => {
    server.use(
      http.get(`${BASE}/v1/stock/a%2Fb`, () => HttpResponse.json(stock('a/b', 5, 0))),
    )

    await expect(client().getStock('a/b')).resolves.toMatchObject({ sku: 'a/b' })
  })

  it('builds the listing queries', async () => {
    const urls: string[] = []
    server.use(
      http.get(`${BASE}/v1/stock`, ({ request }) => {
        urls.push(request.url)
        return HttpResponse.json({ items: [] })
      }),
      http.get(`${BASE}/v1/reservations`, ({ request }) => {
        urls.push(request.url)
        return HttpResponse.json({ items: [] })
      }),
    )

    await client().listStock(25, 'bbb')
    await client().listReservations('HELD', 10)

    expect(urls[0]).toContain('limit=25')
    expect(urls[0]).toContain('after=bbb')
    expect(urls[1]).toContain('state=HELD')
  })
})
