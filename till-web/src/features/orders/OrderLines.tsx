import { Link } from 'react-router'
import type { Order } from '../../api/types'
import { money } from '../../format'
import { GameCover } from '../catalogue/GameCover'

/**
 * What an order is for, at the prices it was placed at — which the store fixed when the order was
 * placed, and which a later price change in the catalogue does not touch.
 */
export function OrderLines({ order }: { order: Order }) {
  return (
    <div>
      <ul className="divide-y divide-hairline">
        {order.lines.map((line) => (
          <li key={line.sku} className="flex items-center gap-4 py-3">
            <Link to={`/games/${line.sku}`} className="shrink-0">
              <GameCover sku={line.sku} motif={line.cover ?? 'orbit'} className="h-16 w-12 rounded-md" />
            </Link>
            <div className="min-w-0 flex-1">
              <Link to={`/games/${line.sku}`} className="block truncate font-medium hover:text-accent">
                {line.title}
              </Link>
              <p className="text-xs text-ink-muted">
                {line.quantity} × {money(line.unitPriceCents, order.currency)}
              </p>
            </div>
            <p className="numeric font-semibold">{money(line.unitPriceCents * line.quantity, order.currency)}</p>
          </li>
        ))}
      </ul>
      <div className="mt-2 flex items-baseline justify-between border-t border-hairline pt-4">
        <span className="font-semibold">Total</span>
        <span className="numeric text-xl font-bold">{money(order.totalCents, order.currency)}</span>
      </div>
    </div>
  )
}
