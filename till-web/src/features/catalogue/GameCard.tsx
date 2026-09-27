import { Link } from 'react-router'
import type { GameCard as Game } from '../../api/types'
import { Icon } from '../../components/Icon'
import { Price } from '../../components/Price'
import { Skeleton } from '../../components/States'
import { useAddToCart } from '../cart/useAddToCart'
import { availabilityOf } from './availability'
import { GameCover } from './GameCover'

/**
 * A game on a shelf or in a grid: cover, title, studio, price.
 *
 * The whole card is one link to the game. The quick-add button sits beside the link rather than inside
 * it — a button nested in a link is two controls a screen reader cannot tell apart and a click that
 * does both.
 */
export function GameCard({ game, rank }: { game: Game; rank?: number }) {
  const availability = availabilityOf(game)
  const addToCart = useAddToCart()
  const buyable = availability.max > 0

  return (
    <article className="group relative">
      <Link to={`/games/${game.sku}`} className="block rounded-xl">
        <div className="relative aspect-[3/4] overflow-hidden rounded-xl shadow-card ring-1 ring-black/5 transition duration-300 group-hover:-translate-y-1 group-hover:shadow-pop">
          <GameCover sku={game.sku} motif={game.cover} className="absolute inset-0 transition duration-500 group-hover:scale-[1.04]" />
          {rank !== undefined && (
            <span className="absolute top-2 left-2 grid size-8 place-items-center rounded-lg bg-black/70 text-sm font-bold text-white backdrop-blur-sm">
              {rank}
            </span>
          )}
          {availability.tone === 'low' && (
            <span className="absolute bottom-2 left-2 rounded-md bg-black/70 px-2 py-1 text-xs font-semibold text-amber-300 backdrop-blur-sm">
              {availability.label}
            </span>
          )}
          {!buyable && (
            <span className="absolute inset-x-0 bottom-0 bg-black/70 py-1.5 text-center text-xs font-semibold tracking-wide text-white uppercase backdrop-blur-sm">
              {availability.label}
            </span>
          )}
        </div>
        <div className="mt-3 space-y-1 px-0.5">
          <h3 className="truncate font-semibold text-ink transition group-hover:text-accent">{game.title}</h3>
          <p className="truncate text-xs text-ink-muted">
            {game.studio} · {game.genre}
          </p>
          <div className="pt-0.5">
            <Price priceCents={game.priceCents} listPriceCents={game.listPriceCents} currency={game.currency} size="sm" />
          </div>
        </div>
      </Link>
      {buyable && (
        <button
          type="button"
          aria-label={`Add ${game.title} to cart`}
          title="Add to cart"
          onClick={() => {
            addToCart(game)
          }}
          className="absolute top-2 right-2 grid size-9 place-items-center rounded-lg bg-black/65 text-white opacity-100 backdrop-blur-sm transition hover:bg-accent hover:text-accent-ink focus-visible:opacity-100 sm:opacity-0 sm:group-hover:opacity-100"
        >
          <Icon name="plus" className="size-5" />
        </button>
      )}
    </article>
  )
}

export function GameCardSkeleton() {
  return (
    <div>
      <Skeleton className="aspect-[3/4] rounded-xl" />
      <Skeleton className="mt-3 h-4 w-3/4" />
      <Skeleton className="mt-2 h-3 w-1/2" />
      <Skeleton className="mt-2 h-4 w-1/3" />
    </div>
  )
}
