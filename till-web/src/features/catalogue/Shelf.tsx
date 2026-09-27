import { useId, useRef } from 'react'
import { Link } from 'react-router'
import type { GameCard as Game } from '../../api/types'
import { Icon } from '../../components/Icon'
import { GameCard, GameCardSkeleton } from './GameCard'

/**
 * A titled row of games that scrolls sideways — "On sale", "Best sellers".
 *
 * Native horizontal scrolling with snap points, so a trackpad, a touch screen and a keyboard all work
 * without a carousel library; the arrow buttons are a convenience for a mouse, and move by a screenful.
 */
export function Shelf({
  title,
  subtitle,
  to,
  games,
  ranked = false,
}: {
  title: string
  subtitle?: string
  to?: string
  games: readonly Game[] | undefined
  ranked?: boolean
}) {
  const heading = useId()
  const scroller = useRef<HTMLDivElement>(null)

  function scroll(direction: 1 | -1) {
    const element = scroller.current
    if (element !== null) {
      element.scrollBy({ left: direction * element.clientWidth * 0.85, behavior: 'smooth' })
    }
  }

  return (
    <section aria-labelledby={heading}>
      <div className="flex items-end justify-between gap-4">
        <div>
          <h2 id={heading} className="text-xl font-bold tracking-tight sm:text-2xl">
            {title}
          </h2>
          {subtitle !== undefined && <p className="mt-0.5 text-sm text-ink-secondary">{subtitle}</p>}
        </div>
        <div className="flex shrink-0 items-center gap-1">
          {to !== undefined && (
            <Link to={to} className="mr-2 inline-flex items-center gap-1 text-sm font-semibold text-accent hover:underline">
              See all
              <Icon name="arrowRight" className="size-4" />
            </Link>
          )}
          <button
            type="button"
            aria-label={`Scroll ${title} back`}
            onClick={() => {
              scroll(-1)
            }}
            className="hidden size-9 place-items-center rounded-lg border border-hairline text-ink-secondary hover:bg-sunken hover:text-ink sm:grid"
          >
            <Icon name="chevronLeft" />
          </button>
          <button
            type="button"
            aria-label={`Scroll ${title} forward`}
            onClick={() => {
              scroll(1)
            }}
            className="hidden size-9 place-items-center rounded-lg border border-hairline text-ink-secondary hover:bg-sunken hover:text-ink sm:grid"
          >
            <Icon name="chevronRight" />
          </button>
        </div>
      </div>
      <div
        ref={scroller}
        className="no-scrollbar -mx-1 mt-4 grid snap-x snap-mandatory auto-cols-[minmax(9.5rem,44%)] grid-flow-col gap-4 overflow-x-auto scroll-smooth px-1 pt-1 pb-3 sm:auto-cols-[12rem] lg:auto-cols-[13rem]"
      >
        {games === undefined
          ? Array.from({ length: 6 }, (_, i) => <GameCardSkeleton key={i} />)
          : games.map((game, i) => (
              <div key={game.sku} className="snap-start">
                <GameCard game={game} {...(ranked ? { rank: i + 1 } : {})} />
              </div>
            ))}
      </div>
    </section>
  )
}
