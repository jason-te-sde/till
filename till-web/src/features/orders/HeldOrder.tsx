import { useState } from 'react'
import { stepKey } from '../../api/idempotency'
import { problemOf } from '../../api/problem'
import { useCancelOrderMutation, usePayOrderMutation } from '../../api/storeApi'
import type { Order } from '../../api/types'
import { button } from '../../components/button'
import { Icon } from '../../components/Icon'
import { ErrorNotice } from '../../components/States'
import { money } from '../../format'
import { Countdown } from './Countdown'

/**
 * An order whose stock is held and waiting for payment: the countdown, Pay, and Cancel.
 *
 * Paying an order is one attempt however many times the button is pressed, so its key is derived from
 * the order — a double click, a retry after "the stock system is briefly unavailable", or the same order
 * paid from two tabs all send one key, and the ledger turns the hold into exactly one sale.
 */
export function HeldOrder({
  order,
  onPaid,
  onCancelled,
  onExpired,
}: {
  order: Order
  onPaid: (order: Order) => void
  onCancelled: (order: Order) => void
  onExpired: () => void
}) {
  const [pay, paying] = usePayOrderMutation()
  const [cancel, cancelling] = useCancelOrderMutation()
  const [confirmingCancel, setConfirmingCancel] = useState(false)
  const problem = problemOf(paying.error ?? cancelling.error)
  const busy = paying.isLoading || cancelling.isLoading

  async function payNow() {
    try {
      onPaid(await pay({ id: order.id, key: stepKey(`order-${order.id}`, 'pay') }).unwrap())
    } catch (error) {
      if (problemOf(error as Parameters<typeof problemOf>[0])?.code === 'ORDER_EXPIRED') {
        onExpired()
      }
    }
  }

  async function cancelNow() {
    try {
      onCancelled(await cancel({ id: order.id, key: stepKey(`order-${order.id}`, 'cancel') }).unwrap())
    } catch {
      setConfirmingCancel(false)
    }
  }

  return (
    <div className="rounded-2xl border border-hairline bg-surface p-6 shadow-card">
      <div className="flex items-center gap-4 rounded-xl bg-accent-soft p-4 text-accent-soft-ink">
        <Icon name="clock" className="size-8 shrink-0" />
        <div>
          <p className="text-sm font-medium">Your copies are held for</p>
          <Countdown deadline={order.expiresAt} onElapsed={onExpired} className="text-3xl" />
        </div>
      </div>
      <p className="mt-4 text-sm text-ink-secondary">
        Nobody else can buy these copies until the timer runs out. Pay before then and they are yours; if it
        runs out, they go back on sale and you can simply place the order again.
      </p>

      {problem !== undefined && problem.code !== 'ORDER_EXPIRED' && (
        <div className="mt-4">
          <ErrorNotice
            compact
            problem={problem}
            onRetry={() => {
              void (paying.error === undefined ? cancelNow() : payNow())
            }}
          />
        </div>
      )}

      <button
        type="button"
        disabled={busy}
        onClick={() => {
          void payNow()
        }}
        className={button('primary', 'lg', 'mt-5 w-full')}
      >
        {paying.isLoading ? 'Paying…' : `Pay ${money(order.totalCents, order.currency)}`}
      </button>
      <p className="mt-2 text-center text-xs text-ink-muted">Demonstration store — no money changes hands.</p>

      <div className="mt-5 border-t border-hairline pt-4 text-center">
        {confirmingCancel ? (
          <div className="flex flex-wrap items-center justify-center gap-2">
            <span className="text-sm text-ink-secondary">Cancel this order and release the copies?</span>
            <button
              type="button"
              disabled={busy}
              onClick={() => {
                void cancelNow()
              }}
              className={button('danger', 'sm')}
            >
              {cancelling.isLoading ? 'Cancelling…' : 'Yes, cancel'}
            </button>
            <button
              type="button"
              onClick={() => {
                setConfirmingCancel(false)
              }}
              className={button('ghost', 'sm')}
            >
              Keep it
            </button>
          </div>
        ) : (
          <button
            type="button"
            disabled={busy}
            onClick={() => {
              setConfirmingCancel(true)
            }}
            className="text-sm font-medium text-ink-secondary hover:text-ink hover:underline"
          >
            Cancel order
          </button>
        )}
      </div>
    </div>
  )
}
