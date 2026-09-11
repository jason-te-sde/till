import type { components } from './schema'
import { problemFrom, TillError, TillUnreachableError } from './errors'

export type Stock = components['schemas']['Stock']
export type StockPage = components['schemas']['StockPage']
export type Line = components['schemas']['Line']
export type Reserved = components['schemas']['Reserved']
export type Committed = components['schemas']['Committed']
export type Released = components['schemas']['Released']
export type Reservation = components['schemas']['Reservation']
export type ReservationPage = components['schemas']['ReservationPage']
export type OutboxPage = components['schemas']['OutboxPage']
export type ReservationState = Reservation['state']

/** Attempts before a call gives up. */
const DEFAULT_ATTEMPTS = 4

export interface ClientOptions {
  readonly baseUrl?: string
  readonly token: string
  readonly attempts?: number
  /** Injected so tests do not wait, and so the retry schedule is something a test can assert on. */
  readonly sleep?: (ms: number) => Promise<void>
  readonly fetch?: typeof globalThis.fetch
}

const wait = (ms: number): Promise<void> =>
  new Promise((resolve) => {
    setTimeout(resolve, ms)
  })

/**
 * The browser half of the till client.
 *
 * Mirrors `TillClient` on the Java side, including the part that matters: a 503 and a dropped
 * connection are retried **with the same idempotency key**, which is the only reason retrying a
 * request that may already have been applied is safe. The key is chosen by the caller, once per
 * attempt at a thing, and reused for every retry of that attempt.
 *
 * A 4xx is never retried. The answer will not change, and retrying a refusal is how a page ends up
 * sending four requests to be told "no" four times.
 */
export class TillClient {
  private readonly baseUrl: string
  private readonly token: string
  private readonly attempts: number
  private readonly sleep: (ms: number) => Promise<void>
  private readonly fetchImpl: typeof globalThis.fetch

  constructor(options: ClientOptions) {
    this.baseUrl = options.baseUrl ?? ''
    this.token = options.token
    this.attempts = options.attempts ?? DEFAULT_ATTEMPTS
    this.sleep = options.sleep ?? wait
    this.fetchImpl = options.fetch ?? globalThis.fetch.bind(globalThis)
  }

  listStock(limit = 100, after?: string): Promise<StockPage> {
    const query = new URLSearchParams({ limit: String(limit) })
    if (after !== undefined) {
      query.set('after', after)
    }
    return this.send<StockPage>('GET', `/v1/stock?${query.toString()}`)
  }

  getStock(sku: string): Promise<Stock> {
    return this.send<Stock>('GET', `/v1/stock/${encodeURIComponent(sku)}`)
  }

  adjustStock(key: string, sku: string, delta: number): Promise<Stock> {
    return this.send<Stock>('POST', `/v1/stock/${encodeURIComponent(sku)}/adjust`, { delta }, key)
  }

  listReservations(state?: ReservationState, limit = 100): Promise<ReservationPage> {
    const query = new URLSearchParams({ limit: String(limit) })
    if (state !== undefined) {
      query.set('state', state)
    }
    return this.send<ReservationPage>('GET', `/v1/reservations?${query.toString()}`)
  }

  getReservation(id: string): Promise<Reservation> {
    return this.send<Reservation>('GET', `/v1/reservations/${encodeURIComponent(id)}`)
  }

  reserve(key: string, lines: readonly Line[], ttlSeconds?: number): Promise<Reserved> {
    const body: { lines: readonly Line[]; ttlSeconds?: number } = { lines }
    if (ttlSeconds !== undefined) {
      body.ttlSeconds = ttlSeconds
    }
    return this.send<Reserved>('POST', '/v1/reservations', body, key)
  }

  commit(key: string, id: string): Promise<Committed> {
    return this.send<Committed>('POST', `/v1/reservations/${encodeURIComponent(id)}/commit`, {}, key)
  }

  release(key: string, id: string): Promise<Released> {
    return this.send<Released>('POST', `/v1/reservations/${encodeURIComponent(id)}/release`, {}, key)
  }

  outbox(limit = 50): Promise<OutboxPage> {
    return this.send<OutboxPage>('GET', `/v1/outbox?limit=${String(limit)}`)
  }

  private async send<T>(
    method: string,
    path: string,
    body?: unknown,
    idempotencyKey?: string,
  ): Promise<T> {
    const headers: Record<string, string> = { Authorization: `Bearer ${this.token}` }
    if (body !== undefined) {
      headers['Content-Type'] = 'application/json'
    }
    if (idempotencyKey !== undefined) {
      headers['Idempotency-Key'] = idempotencyKey
    }

    let lastUnreachable: unknown
    for (let attempt = 1; attempt <= this.attempts; attempt++) {
      if (attempt > 1) {
        // Exponential, with the jitter that stops a hundred retrying tabs arriving together.
        const base = 100 * 2 ** (attempt - 2)
        await this.sleep(base / 2 + Math.random() * base)
      }

      let response: Response
      try {
        response = await this.fetchImpl(`${this.baseUrl}${path}`, {
          method,
          headers,
          body: body === undefined ? null : JSON.stringify(body),
        })
      } catch (cause) {
        // The request may have arrived, been applied, and had its answer lost. Retrying is safe
        // only because the idempotency key goes with it.
        lastUnreachable = cause
        continue
      }

      if (response.status === 503 && attempt < this.attempts) {
        continue
      }
      if (!response.ok) {
        throw problemFrom(response.status, await readJson(response), response.statusText)
      }
      if (response.status === 204) {
        return undefined as T
      }
      return (await response.json()) as T
    }
    throw new TillUnreachableError(lastUnreachable)
  }
}

async function readJson(response: Response): Promise<unknown> {
  try {
    return await response.json()
  } catch {
    // A proxy's HTML error page, most likely. The status is still the useful part, and hiding it
    // behind a parse failure helps nobody.
    return null
  }
}

export { TillError, TillUnreachableError }
export type { Shortfall } from './errors'
