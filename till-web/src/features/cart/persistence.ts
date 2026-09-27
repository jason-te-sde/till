import { MAX_LINES, type CartLine } from './cartSlice'

/** Versioned, so a future change to the line's shape can ignore old carts instead of misreading them. */
export const CART_STORAGE_KEY = 'till.cart.v1'

/**
 * Reads the cart a previous visit left behind.
 *
 * Local storage is input, not state: anything can have written it — an older version of this page,
 * another tab mid-write, a curious customer with the developer tools open. So every line is checked
 * field by field, and anything that is not a well-formed line is dropped rather than trusted.
 *
 * @param storage where to read from
 * @returns the lines that survived
 */
export function loadCart(storage: Storage = localStorage): CartLine[] {
  let parsed: unknown
  try {
    parsed = JSON.parse(storage.getItem(CART_STORAGE_KEY) ?? '[]')
  } catch {
    return []
  }
  if (!Array.isArray(parsed)) {
    return []
  }
  return parsed.filter(isLine).slice(0, MAX_LINES)
}

/**
 * @param lines the cart
 * @param storage where to write it
 */
export function saveCart(lines: readonly CartLine[], storage: Storage = localStorage): void {
  try {
    if (lines.length === 0) {
      storage.removeItem(CART_STORAGE_KEY)
    } else {
      storage.setItem(CART_STORAGE_KEY, JSON.stringify(lines))
    }
  } catch {
    // Storage full, or denied in a private window. The cart still works for this visit.
  }
}

function isLine(value: unknown): value is CartLine {
  if (typeof value !== 'object' || value === null) {
    return false
  }
  const line = value as Record<string, unknown>
  return (
    typeof line['sku'] === 'string' &&
    typeof line['title'] === 'string' &&
    typeof line['studio'] === 'string' &&
    typeof line['cover'] === 'string' &&
    isCents(line['priceCents']) &&
    isCents(line['listPriceCents']) &&
    typeof line['quantity'] === 'number' &&
    Number.isInteger(line['quantity']) &&
    line['quantity'] >= 1
  )
}

function isCents(value: unknown): boolean {
  return typeof value === 'number' && Number.isInteger(value) && value >= 0
}
