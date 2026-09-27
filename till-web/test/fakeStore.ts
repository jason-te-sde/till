import { http, HttpResponse } from 'msw'
import { csrfToken } from '../src/api/csrf'
import type { Facet, GameCard, GameDetail, Me, OpsReservation, OpsStock, Order, Problem, Shortfall } from '../src/api/types'
import { ANONYMOUS, catalogue } from './fixtures'
import { server } from './server'

/** One request as it reached the fake store, for a test to assert on. */
export interface Recorded {
  readonly method: string
  readonly path: string
  readonly search: URLSearchParams
  readonly headers: Headers
  readonly body: unknown
}

/**
 * The store's API, in memory, behind the network interceptor.
 *
 * Not a stub per endpoint but a small model of the store: it has a catalogue with stock, it holds
 * copies when an order is placed and refuses with shortfalls when there are too few, it replays an
 * order for a reused idempotency key, it enforces sign-in, the admin group and the CSRF header — so a
 * page test drives a whole flow and asserts on the requests the page actually sent.
 */
export interface FakeStore {
  me: Me
  games: GameCard[]
  orders: Order[]
  reservations: OpsReservation[]
  readonly requests: Recorded[]
  /** Fails the next write with this problem, once — an outage, a race lost to another customer. */
  failNextWrite: Problem | undefined
}

export function fakeStore(initial: Partial<Pick<FakeStore, 'me' | 'games' | 'orders' | 'reservations'>> = {}): FakeStore {
  const store: FakeStore = {
    me: initial.me ?? ANONYMOUS,
    games: initial.games ?? catalogue(),
    orders: initial.orders ?? [],
    reservations: initial.reservations ?? [],
    requests: [],
    failNextWrite: undefined,
  }
  const byKey = new Map<string, Order>()

  async function record(request: Request): Promise<Recorded> {
    const url = new URL(request.url)
    let body: unknown = undefined
    if (request.method !== 'GET' && request.headers.get('content-type')?.includes('json') === true) {
      body = await request.clone().json()
    }
    const entry = { method: request.method, path: url.pathname, search: url.searchParams, headers: request.headers, body }
    store.requests.push(entry)
    return entry
  }

  /** The checks every write passes through on the real store, in the same order. */
  function refuseWrite(request: Request, needsAdmin = false): Response | undefined {
    if (!store.me.authenticated) return problem(401, 'UNAUTHORIZED', 'sign in to continue')
    if (needsAdmin && !store.me.admin) return problem(403, 'FORBIDDEN', 'your account cannot do this')
    const token = csrfToken()
    if (token === undefined || request.headers.get('X-XSRF-TOKEN') !== token) {
      return problem(403, 'CSRF', 'the request did not carry a valid CSRF token')
    }
    if (store.failNextWrite !== undefined) {
      const failure = store.failNextWrite
      store.failNextWrite = undefined
      return HttpResponse.json(failure, {
        status: failure.status,
        headers: { 'Content-Type': 'application/problem+json' },
      })
    }
    return undefined
  }

  function find(sku: string): GameCard | undefined {
    return store.games.find((candidate) => candidate.sku === sku)
  }

  server.use(
    http.get('*/api/me', async ({ request }) => {
      await record(request)
      return HttpResponse.json(store.me)
    }),

    http.get('*/api/home', async ({ request }) => {
      await record(request)
      return HttpResponse.json({
        featured: store.games.slice(0, 4),
        onSale: store.games.filter((candidate) => candidate.discountPercent > 0),
        bestSellers: store.games.slice(0, 8),
        newReleases: [...store.games].reverse().slice(0, 8),
        genres: facet(store.games.map((candidate) => candidate.genre)),
      })
    }),

    http.get('*/api/games', async ({ request }) => {
      await record(request)
      const params = new URL(request.url).searchParams
      const q = (params.get('q') ?? '').toLowerCase()
      const page = Number(params.get('page') ?? 0)
      const size = Number(params.get('size') ?? 24)
      const matchesOthers = (candidate: GameCard) =>
        (q === '' || candidate.title.toLowerCase().includes(q)) &&
        (params.get('tag') === null || candidate.tags.includes(params.get('tag') ?? '')) &&
        (params.get('onSale') !== 'true' || candidate.discountPercent > 0) &&
        (params.get('maxPriceCents') === null || candidate.priceCents <= Number(params.get('maxPriceCents')))
      const genre = params.get('genre')
      const matching = store.games.filter((candidate) => matchesOthers(candidate) && (genre === null || candidate.genre === genre))
      return HttpResponse.json({
        items: matching.slice(page * size, page * size + size),
        page,
        size,
        total: matching.length,
        facets: {
          genres: facet(store.games.filter(matchesOthers).map((candidate) => candidate.genre)),
          tags: facet(matching.flatMap((candidate) => candidate.tags)),
        },
      })
    }),

    http.get('*/api/games/:sku', async ({ request, params }) => {
      await record(request)
      const found = find(String(params['sku']))
      if (found === undefined) return problem(404, 'NOT_FOUND', `there is no game ${String(params['sku'])}`)
      const detail: GameDetail = {
        game: found,
        description: `${found.blurb}\n\nA second paragraph about ${found.title}.`,
        features: ['One feature', 'Another feature', 'A third feature'],
        related: store.games.filter((candidate) => candidate.sku !== found.sku).slice(0, 4),
      }
      return HttpResponse.json(detail)
    }),

    http.get('*/api/orders', async ({ request }) => {
      await record(request)
      if (!store.me.authenticated) return problem(401, 'UNAUTHORIZED', 'sign in to continue')
      return HttpResponse.json({ items: [...store.orders].reverse() })
    }),

    http.get('*/api/orders/:id', async ({ request, params }) => {
      await record(request)
      if (!store.me.authenticated) return problem(401, 'UNAUTHORIZED', 'sign in to continue')
      const found = store.orders.find((candidate) => candidate.id === params['id'])
      return found === undefined ? problem(404, 'NOT_FOUND', 'there is no such order') : HttpResponse.json(found)
    }),

    http.post('*/api/orders', async ({ request }) => {
      const entry = await record(request)
      const refused = refuseWrite(request)
      if (refused !== undefined) return refused
      const key = request.headers.get('Idempotency-Key')
      if (key === null) return problem(400, 'MISSING_HEADER', 'Idempotency-Key is required')
      const replay = byKey.get(key)
      if (replay !== undefined) return HttpResponse.json(replay, { status: 201 })

      const { lines } = (entry.body ?? { lines: [] }) as { lines: { sku: string; quantity: number }[] }
      const shortfalls: Shortfall[] = lines
        .map((line) => ({ line, found: find(line.sku) }))
        .filter(({ line, found }) => found === undefined || found.available < line.quantity)
        .map(({ line, found }) => ({ sku: line.sku, requested: line.quantity, available: Math.max(0, found?.available ?? 0) }))
      if (shortfalls.length > 0) {
        return HttpResponse.json(
          { title: 'Not enough stock', status: 409, detail: 'not enough stock', code: 'INSUFFICIENT_STOCK', shortfalls } satisfies Problem,
          { status: 409, headers: { 'Content-Type': 'application/problem+json' } },
        )
      }
      const placed: Order = {
        id: crypto.randomUUID(),
        status: 'PENDING',
        currency: 'USD',
        createdAt: new Date().toISOString(),
        expiresAt: new Date(Date.now() + 15 * 60_000).toISOString(),
        closedAt: null,
        lines: lines.map((line) => {
          const found = find(line.sku)
          if (found === undefined) throw new Error('checked above')
          found.available -= line.quantity
          return { sku: found.sku, title: found.title, unitPriceCents: found.priceCents, quantity: line.quantity, cover: found.cover }
        }),
        totalCents: 0,
      }
      placed.totalCents = placed.lines.reduce((total, line) => total + line.unitPriceCents * line.quantity, 0)
      store.orders.push(placed)
      byKey.set(key, placed)
      return HttpResponse.json(placed, { status: 201 })
    }),

    http.post('*/api/orders/:id/:action', async ({ request, params }) => {
      await record(request)
      const refused = refuseWrite(request)
      if (refused !== undefined) return refused
      const index = store.orders.findIndex((candidate) => candidate.id === params['id'])
      const found = store.orders[index]
      if (found === undefined) return problem(404, 'NOT_FOUND', 'there is no such order')
      if (found.status === 'EXPIRED') return problem(410, 'ORDER_EXPIRED', 'the hold ran out')
      const status = params['action'] === 'pay' ? 'PAID' : 'CANCELLED'
      if (found.status === 'PENDING') {
        store.orders[index] = { ...found, status, closedAt: new Date().toISOString() }
      }
      return HttpResponse.json(store.orders[index])
    }),

    http.post('*/api/logout', async ({ request }) => {
      await record(request)
      const refused = refuseWrite(request)
      if (refused !== undefined) return refused
      store.me = ANONYMOUS
      return HttpResponse.json({ redirect: 'http://idp.test/logout?client_id=till-store' })
    }),

    http.get('*/api/ops/stock', async ({ request }) => {
      await record(request)
      if (!store.me.authenticated) return problem(401, 'UNAUTHORIZED', 'sign in to continue')
      if (!store.me.admin) return problem(403, 'FORBIDDEN', 'your account cannot do this')
      return HttpResponse.json({ items: store.games.filter((candidate) => candidate.availabilityAsOf !== null).map(stockOf), nextAfter: null })
    }),

    http.get('*/api/ops/reservations', async ({ request }) => {
      await record(request)
      const state = new URL(request.url).searchParams.get('state')
      return HttpResponse.json({ items: store.reservations.filter((candidate) => state === null || candidate.state === state) })
    }),

    http.get('*/api/ops/outbox', async ({ request }) => {
      await record(request)
      return HttpResponse.json({ backlog: 0, items: [] })
    }),

    http.post('*/api/ops/stock/:sku/adjust', async ({ request, params }) => {
      const entry = await record(request)
      const refused = refuseWrite(request, true)
      if (refused !== undefined) return refused
      const found = find(String(params['sku']))
      if (found === undefined) return problem(404, 'NOT_FOUND', 'no such game')
      const { delta } = entry.body as { delta: number }
      found.available += delta
      return HttpResponse.json(stockOf(found))
    }),
  )
  return store
}

/** The requests a test cares about, without the session checks every page makes. */
export function writes(store: FakeStore): Recorded[] {
  return store.requests.filter((request) => request.method !== 'GET')
}

function stockOf(found: GameCard): OpsStock {
  return { sku: found.sku, title: found.title, onHand: found.available, reserved: 0, available: found.available }
}

function facet(values: string[]): Facet[] {
  const counts = new Map<string, number>()
  for (const value of values) counts.set(value, (counts.get(value) ?? 0) + 1)
  return [...counts].map(([value, count]) => ({ value, count }))
}

function problem(status: number, code: string, detail: string): Response {
  return HttpResponse.json({ type: 'about:blank', title: code, status, detail, code } satisfies Problem, {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  })
}
