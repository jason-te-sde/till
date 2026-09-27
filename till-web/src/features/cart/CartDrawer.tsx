import { Link } from 'react-router'
import { useAppDispatch, useAppSelector } from '../../app/hooks'
import { cartClosed, selectCartOpen } from '../../app/uiSlice'
import { button } from '../../components/button'
import { Icon } from '../../components/Icon'
import { Modal } from '../../components/Modal'
import { QuantityStepper } from '../../components/QuantityStepper'
import { money, plural } from '../../format'
import { GameCover } from '../catalogue/GameCover'
import { MAX_QUANTITY, quantitySet, removed, selectCartCount, selectCartLines, selectCartSavings, selectCartSubtotal } from './cartSlice'

/**
 * The cart, as a drawer from the right: adjust, remove, and go to checkout.
 *
 * The totals here are at the prices the games had when they were added. The order is priced again by
 * the store when it is placed, and the checkout says so — a cart is a list, not a quote.
 */
export function CartDrawer() {
  const open = useAppSelector(selectCartOpen)
  const lines = useAppSelector(selectCartLines)
  const count = useAppSelector(selectCartCount)
  const subtotal = useAppSelector(selectCartSubtotal)
  const savings = useAppSelector(selectCartSavings)
  const dispatch = useAppDispatch()
  const close = () => {
    dispatch(cartClosed())
  }

  return (
    <Modal
      open={open}
      onClose={close}
      title={count === 0 ? 'Your cart' : `Your cart (${plural(count, 'item')})`}
      side
      {...(lines.length === 0
        ? {}
        : {
            footer: (
              <div className="space-y-3">
                {savings > 0 && (
                  <div className="flex justify-between text-sm text-good-ink">
                    <span>You save</span>
                    <span className="numeric font-semibold">{money(savings)}</span>
                  </div>
                )}
                <div className="flex items-baseline justify-between">
                  <span className="font-semibold">Subtotal</span>
                  <span className="numeric text-xl font-bold">{money(subtotal)}</span>
                </div>
                <Link to="/checkout" onClick={close} className={button('primary', 'lg', 'w-full')}>
                  Check out
                  <Icon name="arrowRight" />
                </Link>
              </div>
            ),
          })}
    >
      {lines.length === 0 ? (
        <div className="flex h-full flex-col items-center justify-center py-16 text-center">
          <span className="grid size-14 place-items-center rounded-full bg-accent-soft text-accent-soft-ink">
            <Icon name="cart" className="size-7" />
          </span>
          <p className="mt-4 font-semibold">Your cart is empty</p>
          <p className="mt-1 text-sm text-ink-secondary">Games you add will wait for you here.</p>
          <Link to="/browse" onClick={close} className={button('secondary', 'md', 'mt-6')}>
            Browse games
          </Link>
        </div>
      ) : (
        <ul className="divide-y divide-hairline">
          {lines.map((line) => (
            <li key={line.sku} className="flex gap-4 py-4 first:pt-1">
              <Link to={`/games/${line.sku}`} onClick={close} className="shrink-0">
                <GameCover sku={line.sku} motif={line.cover} className="h-24 w-18 rounded-lg" />
              </Link>
              <div className="flex min-w-0 flex-1 flex-col">
                <div className="flex items-start justify-between gap-3">
                  <div className="min-w-0">
                    <Link to={`/games/${line.sku}`} onClick={close} className="block truncate font-semibold hover:text-accent">
                      {line.title}
                    </Link>
                    <p className="truncate text-xs text-ink-muted">{line.studio}</p>
                  </div>
                  <button
                    type="button"
                    aria-label={`Remove ${line.title}`}
                    onClick={() => {
                      dispatch(removed(line.sku))
                    }}
                    className="rounded-md p-1 text-ink-muted hover:bg-sunken hover:text-critical-ink"
                  >
                    <Icon name="trash" className="size-4" />
                  </button>
                </div>
                <div className="mt-auto flex items-end justify-between pt-3">
                  <QuantityStepper
                    size="sm"
                    value={line.quantity}
                    max={MAX_QUANTITY}
                    label={`copies of ${line.title}`}
                    onChange={(quantity) => {
                      dispatch(quantitySet({ sku: line.sku, quantity }))
                    }}
                  />
                  <div className="text-right">
                    {line.listPriceCents > line.priceCents && (
                      <p className="numeric text-xs text-ink-muted line-through">{money(line.listPriceCents * line.quantity)}</p>
                    )}
                    <p className="numeric font-semibold">{money(line.priceCents * line.quantity)}</p>
                  </div>
                </div>
              </div>
            </li>
          ))}
        </ul>
      )}
    </Modal>
  )
}
