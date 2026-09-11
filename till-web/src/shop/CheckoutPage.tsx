import { ErrorNotice } from '../components/ErrorNotice'
import { money } from '../format'
import { Countdown } from './Countdown'
import { BY_SKU } from './catalogue'
import { total, toLines, type Cart } from './cart'
import type { Checkout } from './useCheckout'

/**
 * The checkout screen.
 *
 * Renders a {@link Checkout}, and does nothing else. Every transition lives in the hook, which is
 * where the tests are and where the idempotency keys are decided; this file decides what a phase
 * looks like.
 */
export function CheckoutPage({
  cart,
  checkout,
  onBack,
}: {
  cart: Cart
  checkout: Checkout
  onBack: (clearCart: boolean) => void
}) {
  const { phase, attemptKey } = checkout

  return (
    <div className="mx-auto max-w-xl space-y-5">
      <header>
        <h1 className="text-xl font-semibold">Checkout</h1>
        {attemptKey !== null && (
          <p className="numeric mt-1 text-sm break-all text-ink-secondary">
            attempt key <code className="text-ink">{attemptKey}</code>
          </p>
        )}
      </header>

      <section className="rounded-lg border border-hairline bg-surface">
        <ul className="divide-y divide-hairline">
          {toLines(cart).map((line) => (
            <li key={line.sku} className="flex items-center justify-between px-4 py-2.5 text-sm">
              <span>
                <span aria-hidden="true" className="mr-1.5">
                  {BY_SKU.get(line.sku)?.emoji}
                </span>
                {BY_SKU.get(line.sku)?.name ?? line.sku}
                <span className="numeric ml-2 text-ink-secondary">×{line.quantity}</span>
              </span>
              <span className="numeric">
                {money((BY_SKU.get(line.sku)?.pence ?? 0) * line.quantity)}
              </span>
            </li>
          ))}
        </ul>
        <div className="flex items-center justify-between border-t border-hairline px-4 py-2.5 font-medium">
          <span>Total</span>
          <span className="numeric">{money(total(cart))}</span>
        </div>
      </section>

      {phase.kind === 'holding' && (
        <p className="text-sm text-ink-secondary">Setting the stock aside…</p>
      )}

      {(phase.kind === 'held' || phase.kind === 'paying') && (
        <section className="space-y-4 rounded-lg border border-hairline bg-surface p-4">
          <p className="text-sm">
            Held for you — <Countdown deadline={phase.hold.expiresAt} onElapsed={checkout.expire} />{' '}
            left. After that it goes back, whether or not anything has swept.
          </p>
          <div className="flex flex-wrap gap-2">
            <button
              type="button"
              onClick={() => {
                void checkout.pay()
              }}
              className="rounded-md bg-ink px-4 py-2 font-medium text-surface disabled:opacity-50"
              disabled={phase.kind === 'paying'}
            >
              {phase.kind === 'paying' ? 'Paying…' : `Pay ${money(total(cart))}`}
            </button>
            <button
              type="button"
              onClick={() => {
                void checkout.cancel()
              }}
              className="rounded-md border border-line-strong px-4 py-2 font-medium hover:bg-sunken"
            >
              Cancel
            </button>
          </div>
          <p className="text-xs text-ink-muted">
            Click Pay twice on purpose. Both requests carry the same key, so the second is answered
            from the first and there is one sale.
          </p>
        </section>
      )}

      {phase.kind === 'paid' && (
        <section
          className="rounded-lg border border-hairline bg-surface p-4"
          style={{ borderLeft: '3px solid var(--status-good)' }}
        >
          <p className="font-medium" style={{ color: 'var(--status-good-ink)' }}>
            Paid
          </p>
          <p className="numeric mt-1 text-sm break-all text-ink-secondary">
            {phase.hold.id} · {new Date(phase.at).toLocaleTimeString()}
          </p>
          <p className="mt-2 text-sm text-ink-secondary">
            On-hand fell by what you bought, and the hold went with it.
          </p>
        </section>
      )}

      {phase.kind === 'gone' && (
        <section
          className="rounded-lg border border-hairline bg-surface p-4"
          style={{ borderLeft: '3px solid var(--status-warning)' }}
        >
          <p className="font-medium">
            {phase.why === 'expired' ? 'That hold ran out' : 'Cancelled'}
          </p>
          <p className="mt-1 text-sm text-ink-secondary">
            {phase.why === 'expired'
              ? 'The stock is back for somebody else. Nothing had to sweep for that to be true — the deadline is what decides.'
              : 'The stock went straight back.'}
          </p>
        </section>
      )}

      {phase.kind === 'refused' && <ErrorNotice error={phase.error} />}

      <div className="flex flex-wrap gap-2">
        {phase.kind === 'refused' && (
          <button
            type="button"
            onClick={() => {
              void checkout.retry(cart)
            }}
            className="rounded-md border border-line-strong px-4 py-2 font-medium hover:bg-sunken"
          >
            Try this basket again
          </button>
        )}
        <button
          type="button"
          onClick={() => {
            onBack(phase.kind === 'paid')
          }}
          className="rounded-md border border-line-strong px-4 py-2 font-medium hover:bg-sunken"
        >
          {phase.kind === 'held' || phase.kind === 'paying'
            ? 'Back to the shop (keeps the hold)'
            : 'Back to the shop'}
        </button>
      </div>
    </div>
  )
}
