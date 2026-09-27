import type { Shortfall } from '../../api/types'
import { useAppDispatch } from '../../app/hooks'
import { button } from '../../components/button'
import { Icon } from '../../components/Icon'
import { quantitySet, removed, type CartLine } from '../cart/cartSlice'

/**
 * "Somebody bought the last copies first" — said precisely, with the fix one click away.
 *
 * The store passes the ledger's shortfalls through: for each game, how many were asked for and how
 * many could have been had. So instead of a dead end, the customer is offered the copies that are
 * left. Changing the cart changes the checkout's idempotency key, so the next attempt is a new request
 * rather than a replay of the refused one.
 */
export function ShortfallNotice({ shortfalls, lines }: { shortfalls: readonly Shortfall[]; lines: readonly CartLine[] }) {
  const dispatch = useAppDispatch()
  const fixes = shortfalls.map((shortfall) => ({
    shortfall,
    title: lines.find((line) => line.sku === shortfall.sku)?.title ?? shortfall.sku,
  }))

  function fixAll() {
    for (const { shortfall } of fixes) {
      if (shortfall.available > 0) {
        dispatch(quantitySet({ sku: shortfall.sku, quantity: shortfall.available }))
      } else {
        dispatch(removed(shortfall.sku))
      }
    }
  }

  return (
    <div role="alert" className="rounded-2xl border border-line-strong bg-surface p-5">
      <div className="flex items-start gap-3">
        <Icon name="alert" className="mt-0.5 size-5 shrink-0 text-warning-ink" />
        <div className="min-w-0 flex-1">
          <p className="font-semibold">Not enough copies left</p>
          <p className="mt-0.5 text-sm text-ink-secondary">
            Other players got there first. Here is what is still available:
          </p>
          <ul className="mt-3 space-y-2">
            {fixes.map(({ shortfall, title }) => (
              <li key={shortfall.sku} className="flex flex-wrap items-center justify-between gap-2 rounded-lg bg-sunken px-3 py-2 text-sm">
                <span>
                  <span className="font-medium">{title}</span>
                  <span className="text-ink-secondary">
                    {' '}
                    — you asked for {shortfall.requested},{' '}
                    {shortfall.available > 0 ? `${String(shortfall.available)} left` : 'none left'}
                  </span>
                </span>
                {shortfall.available > 0 ? (
                  <button
                    type="button"
                    onClick={() => {
                      dispatch(quantitySet({ sku: shortfall.sku, quantity: shortfall.available }))
                    }}
                    className={button('secondary', 'sm')}
                  >
                    Keep {shortfall.available}
                  </button>
                ) : (
                  <button
                    type="button"
                    onClick={() => {
                      dispatch(removed(shortfall.sku))
                    }}
                    className={button('secondary', 'sm')}
                  >
                    Remove
                  </button>
                )}
              </li>
            ))}
          </ul>
          {fixes.length > 1 && (
            <button type="button" onClick={fixAll} className={button('primary', 'sm', 'mt-3')}>
              Update my cart
            </button>
          )}
        </div>
      </div>
    </div>
  )
}
