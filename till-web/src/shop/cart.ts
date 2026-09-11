import type { Line } from '../api/client'
import { BY_SKU } from './catalogue'

/** What is in the basket, as quantities by SKU. */
export type Cart = ReadonlyMap<string, number>

export const EMPTY: Cart = new Map()

/**
 * Adds to the basket, capped at what the shop believes is available.
 *
 * The cap is a courtesy, not a guarantee — the number it checks against was read some time ago and
 * anybody could have taken the last one since. The only thing that decides is the reservation, which
 * is why this returns a cart rather than a promise and why the checkout page has to handle a refusal
 * however careful this was.
 */
export function add(cart: Cart, sku: string, available: number): Cart {
  const next = new Map(cart)
  const current = next.get(sku) ?? 0
  if (current >= available) {
    return cart
  }
  next.set(sku, current + 1)
  return next
}

export function remove(cart: Cart, sku: string): Cart {
  const next = new Map(cart)
  const current = next.get(sku) ?? 0
  if (current <= 1) {
    next.delete(sku)
  } else {
    next.set(sku, current - 1)
  }
  return next
}

export function count(cart: Cart): number {
  let total = 0
  for (const quantity of cart.values()) {
    total += quantity
  }
  return total
}

export function total(cart: Cart): number {
  let pence = 0
  for (const [sku, quantity] of cart) {
    pence += (BY_SKU.get(sku)?.pence ?? 0) * quantity
  }
  return pence
}

/**
 * The basket as reservation lines.
 *
 * Sorted by SKU, because the service canonicalises them anyway and a stable order means two carts
 * holding the same things produce the same request — which is what makes the idempotency fingerprint
 * recognise a retry.
 */
export function toLines(cart: Cart): Line[] {
  return [...cart]
    .map(([sku, quantity]) => ({ sku, quantity }))
    .sort((left, right) => left.sku.localeCompare(right.sku))
}
