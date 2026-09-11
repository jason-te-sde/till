import { useCallback, useMemo, useState } from 'react'
import { useTill } from '../api/context'
import { TillError, type Stock } from '../api/client'
import { newAttemptKey } from '../api/idempotency'
import { usePoll } from '../components/usePoll'
import { ErrorNotice } from '../components/ErrorNotice'
import { BY_SKU, CATALOGUE } from './catalogue'
import { money } from '../format'
import { add, count, EMPTY, remove, total, toLines, type Cart } from './cart'
import { CheckoutPage } from './CheckoutPage'
import { useCheckout } from './useCheckout'

const REFRESH_MS = 2000

/**
 * The shop front, and the reason reservations exist.
 *
 * Available counts are re-read every couple of seconds, so two of these side by side show each other
 * taking stock. What they cannot do is agree in advance about who gets the last one — that is decided
 * when a hold is taken, and the interesting half of this page is what happens when it is refused.
 */
export function ShopPage() {
  const { client } = useTill()
  const [cart, setCart] = useState<Cart>(EMPTY)
  const [restockError, setRestockError] = useState<unknown>(undefined)
  const checkout = useCheckout(client)

  const readStock = useCallback(() => client.listStock(50), [client])
  const stock = usePoll(readStock, REFRESH_MS)

  const levels = useMemo(() => {
    const map = new Map<string, Stock>()
    for (const item of stock.data?.items ?? []) {
      map.set(item.sku, item)
    }
    return map
  }, [stock.data])

  const restock = async () => {
    setRestockError(undefined)
    try {
      // One key per SKU per click, because each is a separate delivery arriving.
      await Promise.all(
        CATALOGUE.map((product) =>
          client.adjustStock(newAttemptKey(`restock-${product.sku}`), product.sku, 25),
        ),
      )
      stock.refresh()
    } catch (cause: unknown) {
      setRestockError(cause)
    }
  }

  if (checkout.phase.kind !== 'shopping') {
    return (
      <CheckoutPage
        cart={cart}
        checkout={checkout}
        onBack={(clearCart) => {
          checkout.reset()
          if (clearCart) {
            setCart(EMPTY)
          }
          stock.refresh()
        }}
      />
    )
  }

  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold">Shop</h1>
          <p className="text-sm text-ink-secondary">
            Counts refresh every {REFRESH_MS / 1000}s. Open this page twice and race yourself for the
            last one.
          </p>
        </div>
        <button
          type="button"
          onClick={() => {
            void restock()
          }}
          className="rounded-md border border-line-strong px-3 py-1.5 text-sm font-medium hover:bg-sunken"
        >
          Restock everything (+25)
        </button>
      </header>

      {restockError !== undefined && (
        <ErrorNotice
          error={
            restockError instanceof TillError && restockError.status === 403
              ? new TillError(
                  403,
                  'FORBIDDEN',
                  'Restocking changes stock levels directly, which needs the admin token. Sign in again with it, or use tillctl.',
                )
              : restockError
          }
        />
      )}
      {stock.error !== undefined && <ErrorNotice error={stock.error} onRetry={stock.refresh} />}

      <ul className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {CATALOGUE.map((product) => {
          const level = levels.get(product.sku)
          const available = level?.available ?? 0
          const inCart = cart.get(product.sku) ?? 0
          const unstocked = level === undefined

          return (
            <li
              key={product.sku}
              className="flex flex-col rounded-lg border border-hairline bg-surface p-4"
            >
              <div className="flex items-start justify-between gap-2">
                <div>
                  <h2 className="font-medium">
                    <span aria-hidden="true" className="mr-1.5">
                      {product.emoji}
                    </span>
                    {product.name}
                  </h2>
                  <p className="text-sm text-ink-secondary">{product.blurb}</p>
                </div>
                <span className="numeric shrink-0 font-medium">{money(product.pence)}</span>
              </div>

              <p className="numeric mt-3 text-sm text-ink-secondary">
                {unstocked ? (
                  'Never stocked'
                ) : available > 0 ? (
                  <>
                    <span className="font-medium text-ink">{available}</span> available
                    {level.reserved > 0 && <> · {level.reserved} held by somebody</>}
                  </>
                ) : (
                  <span style={{ color: 'var(--status-warning)' }}>
                    Out of stock{level.reserved > 0 && <> · {level.reserved} held</>}
                  </span>
                )}
              </p>

              <div className="mt-4 flex items-center gap-2">
                <button
                  type="button"
                  disabled={unstocked || inCart >= available}
                  onClick={() => {
                    setCart((current) => add(current, product.sku, available))
                  }}
                  className="rounded-md bg-ink px-3 py-1.5 text-sm font-medium text-surface disabled:opacity-40"
                >
                  Add to basket
                </button>
                {inCart > 0 && (
                  <>
                    <button
                      type="button"
                      aria-label={`Remove one ${product.name}`}
                      onClick={() => {
                        setCart((current) => remove(current, product.sku))
                      }}
                      className="rounded-md border border-line-strong px-2 py-1.5 text-sm hover:bg-sunken"
                    >
                      −
                    </button>
                    <span className="numeric text-sm text-ink-secondary">{inCart} in basket</span>
                  </>
                )}
              </div>
            </li>
          )
        })}
      </ul>

      <footer className="sticky bottom-4 rounded-lg border border-hairline bg-surface px-4 py-3 shadow-sm">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <p className="numeric text-sm">
            {count(cart) === 0 ? (
              <span className="text-ink-secondary">Basket empty</span>
            ) : (
              <>
                <span className="font-medium">{count(cart)}</span> item
                {count(cart) === 1 ? '' : 's'} ·{' '}
                <span className="font-medium">{money(total(cart))}</span>
                <span className="ml-2 text-ink-secondary">
                  {toLines(cart)
                    .map((line) => `${BY_SKU.get(line.sku)?.name ?? line.sku}×${String(line.quantity)}`)
                    .join(', ')}
                </span>
              </>
            )}
          </p>
          <button
            type="button"
            disabled={count(cart) === 0}
            onClick={() => {
              // Taking the hold happens here, in the click, rather than when the checkout screen
              // renders. Reserving is a mutation, and a component that mutates while rendering does
              // it again on every re-mount.
              void checkout.start(cart)
            }}
            className="rounded-md bg-ink px-4 py-2 font-medium text-surface disabled:opacity-40"
          >
            Check out
          </button>
        </div>
      </footer>
    </div>
  )
}
