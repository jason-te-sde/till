import { useCallback, useState } from 'react'
import type { Reserved, TillClient } from '../api/client'
import { TillError } from '../api/client'
import { newAttemptKey, stepKey } from '../api/idempotency'
import { toLines, type Cart } from './cart'

/** Seconds a shop hold lasts. Short, because this is a demonstration and nobody waits fifteen minutes. */
export const HOLD_SECONDS = 120

export type Phase =
  | { readonly kind: 'shopping' }
  | { readonly kind: 'holding' }
  | { readonly kind: 'held'; readonly hold: Reserved }
  | { readonly kind: 'paying'; readonly hold: Reserved }
  | { readonly kind: 'paid'; readonly hold: Reserved; readonly at: string }
  | { readonly kind: 'gone'; readonly hold: Reserved; readonly why: 'cancelled' | 'expired' }
  | { readonly kind: 'refused'; readonly error: unknown }

export interface Checkout {
  readonly phase: Phase
  /** The key for this attempt, so a page can show it. Null before an attempt has started. */
  readonly attemptKey: string | null
  readonly start: (cart: Cart) => Promise<void>
  readonly retry: (cart: Cart) => Promise<void>
  readonly pay: () => Promise<void>
  readonly cancel: () => Promise<void>
  readonly reset: () => void
  readonly expire: () => void
}

/**
 * The fifteen minutes between wanting something and having paid for it.
 *
 * A hook rather than a component, for two reasons. It is the part worth testing on its own — every
 * interesting transition here is a failure mode — and it keeps the reserve in an **event handler**
 * rather than in a mount effect. That second point is not a lint technicality: taking a hold is a
 * mutation, a component that takes one while rendering takes another one when it re-mounts, and in
 * development React mounts everything twice. The idempotency key would cover for it, which is
 * exactly why relying on that would be the wrong reason not to fix it.
 *
 * One attempt key per attempt, reused by every retry within it, with each step deriving its own from
 * it. A dropped connection, a double-clicked Pay and a reloaded tab all produce one order.
 */
export function useCheckout(client: TillClient): Checkout {
  const [phase, setPhase] = useState<Phase>({ kind: 'shopping' })
  const [attemptKey, setAttemptKey] = useState<string | null>(null)

  const take = useCallback(
    async (cart: Cart, key: string) => {
      setAttemptKey(key)
      setPhase({ kind: 'holding' })
      try {
        const hold = await client.reserve(key, toLines(cart), HOLD_SECONDS)
        setPhase({ kind: 'held', hold })
      } catch (error: unknown) {
        setPhase({ kind: 'refused', error })
      }
    },
    [client],
  )

  const start = useCallback(
    (cart: Cart) => take(cart, newAttemptKey('checkout')),
    [take],
  )

  const retry = useCallback(
    (cart: Cart) => take(cart, attemptKey ?? newAttemptKey('checkout')),
    [attemptKey, take],
  )

  const pay = useCallback(async () => {
    if (phase.kind !== 'held' || attemptKey === null) {
      return
    }
    const { hold } = phase
    setPhase({ kind: 'paying', hold })
    try {
      const committed = await client.commit(stepKey(attemptKey, 'pay'), hold.id)
      setPhase({ kind: 'paid', hold, at: committed.committedAt })
    } catch (error: unknown) {
      if (error instanceof TillError && error.isExpired) {
        setPhase({ kind: 'gone', hold, why: 'expired' })
        return
      }
      setPhase({ kind: 'refused', error })
    }
  }, [attemptKey, client, phase])

  const cancel = useCallback(async () => {
    if (phase.kind !== 'held' || attemptKey === null) {
      return
    }
    const { hold } = phase
    try {
      await client.release(stepKey(attemptKey, 'cancel'), hold.id)
    } catch {
      // Releasing is idempotent by nature and the hold expires on its own regardless, so a failure
      // here changes nothing a customer can see. Reporting it would be noise about nothing.
    }
    setPhase({ kind: 'gone', hold, why: 'cancelled' })
  }, [attemptKey, client, phase])

  const expire = useCallback(() => {
    setPhase((current) =>
      current.kind === 'held' ? { kind: 'gone', hold: current.hold, why: 'expired' } : current,
    )
  }, [])

  const reset = useCallback(() => {
    setPhase({ kind: 'shopping' })
    setAttemptKey(null)
  }, [])

  return { phase, attemptKey, start, retry, pay, cancel, reset, expire }
}
