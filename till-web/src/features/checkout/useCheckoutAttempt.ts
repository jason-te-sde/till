import { useCallback, useEffect, useState } from 'react'
import { newAttemptKey, stepKey } from '../../api/idempotency'
import type { CartLine } from '../cart/cartSlice'

const STORAGE_KEY = 'till.checkout.v1'

interface Attempt {
  /** Names this checkout attempt; a new one after an order is paid, cancelled or lets its hold lapse. */
  readonly salt: string
  /** The order this attempt placed, once it has placed one. */
  readonly orderId?: string
}

/**
 * The idempotency of one checkout, which is subtler than one key.
 *
 * The key for placing an order is the attempt's salt plus a fingerprint of the cart. So:
 *
 * - **Retrying the same cart sends the same key.** A double click, a reload mid-request or a response
 *   lost on the way back all replay the order already placed instead of holding the stock twice.
 * - **Changing the cart changes the key.** After "only 1 left — keep 1?", the next attempt is for a
 *   different basket, and reusing the old key would rightly be refused by the store as a key reused for
 *   a different request.
 * - **Finishing starts a new attempt.** Once an order is paid, cancelled or expired, the same cart
 *   bought again next week is a new purchase, not a replay of last week's.
 *
 * Kept in session storage, so a reload during checkout resumes the order it placed rather than
 * starting another — and a new tab, which is a new session, starts clean.
 *
 * @param lines the cart
 * @returns the key to place the order with, the order placed so far, and the transitions
 */
export function useCheckoutAttempt(lines: readonly CartLine[]) {
  const [attempt, setAttempt] = useState<Attempt>(() => read() ?? { salt: newAttemptKey('checkout') })

  useEffect(() => {
    try {
      sessionStorage.setItem(STORAGE_KEY, JSON.stringify(attempt))
    } catch {
      // Storage denied: the attempt lasts as long as the page, which is most of the protection.
    }
  }, [attempt])

  // Stable across renders, so a page can depend on them in an effect without re-running it each time.
  const placed = useCallback((orderId: string) => {
    setAttempt((current) => ({ ...current, orderId }))
  }, [])
  const finished = useCallback(() => {
    setAttempt({ salt: newAttemptKey('checkout') })
  }, [])

  return { key: stepKey(attempt.salt, fingerprint(lines)), orderId: attempt.orderId, placed, finished }
}

export type CheckoutAttempt = ReturnType<typeof useCheckoutAttempt>

/**
 * The cart as a short, order-independent string: the same games in the same quantities always give the
 * same fingerprint, whichever order they were added in.
 *
 * @param lines the cart
 * @returns eight hex digits
 */
export function fingerprint(lines: readonly { sku: string; quantity: number }[]): string {
  const canonical = lines
    .map((line) => `${line.sku}*${String(line.quantity)}`)
    .sort()
    .join(',')
  let hash = 0x811c9dc5
  for (let i = 0; i < canonical.length; i++) {
    hash ^= canonical.charCodeAt(i)
    hash = Math.imul(hash, 0x01000193)
  }
  return (hash >>> 0).toString(16).padStart(8, '0')
}

function read(): Attempt | undefined {
  try {
    const parsed: unknown = JSON.parse(sessionStorage.getItem(STORAGE_KEY) ?? 'null')
    if (typeof parsed === 'object' && parsed !== null && typeof (parsed as Attempt).salt === 'string') {
      const { salt, orderId } = parsed as Attempt
      return typeof orderId === 'string' ? { salt, orderId } : { salt }
    }
  } catch {
    // Unreadable: start a new attempt.
  }
  return undefined
}
