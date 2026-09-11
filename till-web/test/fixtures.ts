import { http, HttpResponse, type HttpHandler } from 'msw'
import type { OutboxPage, Reservation, Stock, StockPage } from '../src/api/client'

export const BASE = 'http://localhost'

export function stock(sku: string, onHand: number, reserved: number): Stock {
  return { sku, onHand, reserved, available: onHand - reserved }
}

export function stockPage(items: Stock[], nextAfter?: string): StockPage {
  return nextAfter === undefined ? { items } : { items, nextAfter }
}

export function reservation(
  id: string,
  state: Reservation['state'],
  effectiveState: Reservation['state'] = state,
): Reservation {
  return {
    id,
    state,
    effectiveState,
    lines: [{ sku: 'widget', quantity: 2 }],
    createdAt: '2026-09-11T12:00:00Z',
    expiresAt: '2026-09-11T12:02:00Z',
  }
}

export function outbox(backlog: number, keys: string[]): OutboxPage {
  return {
    backlog,
    items: keys.map((dedupeKey, index) => ({
      sequence: index + 1,
      dedupeKey,
      recordedAt: '2026-09-11T12:00:00Z',
      payload: `v1 ${dedupeKey}`,
    })),
  }
}

/** How far short one SKU fell, as the service reports it. */
interface ShortfallBody {
  sku: string
  requested: number
  available: number
}

/**
 * A problem body in the shape the service actually sends.
 *
 * The return type is inferred rather than written as `HttpResponse`: that type is generic over its
 * body, and annotating it unparameterised loses the body type — which the strict lint rules then
 * report as an unsafe return at every call site.
 */
export function problem(
  status: number,
  code: string,
  detail: string,
  shortfalls?: ShortfallBody[],
) {
  return HttpResponse.json(
    shortfalls === undefined ? { status, code, detail } : { status, code, detail, shortfalls },
    { status },
  )
}

/** Handlers for the read endpoints, so a page test does not have to stub every one it does not care about. */
export function quietReads(): HttpHandler[] {
  return [
    http.get(`${BASE}/v1/stock`, () => HttpResponse.json(stockPage([]))),
    http.get(`${BASE}/v1/reservations`, () => HttpResponse.json({ items: [] })),
    http.get(`${BASE}/v1/outbox`, () => HttpResponse.json(outbox(0, []))),
  ]
}
