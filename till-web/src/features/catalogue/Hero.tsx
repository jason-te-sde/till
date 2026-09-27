import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import type { GameCard as Game } from '../../api/types'
import { button } from '../../components/button'
import { Icon } from '../../components/Icon'
import { Price } from '../../components/Price'
import { Skeleton } from '../../components/States'
import { releaseDate } from '../../format'
import { useAddToCart } from '../cart/useAddToCart'
import { availabilityOf } from './availability'
import { GameCover } from './GameCover'

/** Long enough to read a blurb; short enough that the row turns over while somebody is looking. */
const ADVANCE_MS = 7000

/**
 * The featured games, one at a time, across the top of the home page.
 *
 * It advances on its own and stops the moment it has somebody's attention — a pointer over it or focus
 * inside it — because a slide that changes under a reader's cursor is the classic carousel failure.
 * With reduced motion requested it does not advance at all.
 */
export function Hero({ games }: { games: readonly Game[] }) {
  const [index, setIndex] = useState(0)
  const [held, setHeld] = useState(false)
  const addToCart = useAddToCart()

  useEffect(() => {
    if (held || games.length < 2 || window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      return
    }
    const timer = setInterval(() => {
      setIndex((current) => (current + 1) % games.length)
    }, ADVANCE_MS)
    return () => {
      clearInterval(timer)
    }
  }, [held, games.length])

  const game = games[index % Math.max(1, games.length)]
  if (game === undefined) {
    return null
  }
  const availability = availabilityOf(game)

  return (
    <section
      aria-roledescription="carousel"
      aria-label="Featured games"
      onPointerEnter={() => {
        setHeld(true)
      }}
      onPointerLeave={() => {
        setHeld(false)
      }}
      onFocus={() => {
        setHeld(true)
      }}
      onBlur={() => {
        setHeld(false)
      }}
      className="relative isolate overflow-hidden rounded-3xl bg-black shadow-pop"
    >
      {games.map((featured, i) => (
        <div
          key={featured.sku}
          aria-hidden="true"
          className={`absolute inset-0 transition-opacity duration-700 ${i === index ? 'opacity-100' : 'opacity-0'}`}
        >
          <GameCover sku={featured.sku} motif={featured.cover} className="size-full" />
        </div>
      ))}
      <div aria-hidden="true" className="absolute inset-0 bg-gradient-to-r from-black/90 via-black/55 to-black/5" />
      <div aria-hidden="true" className="absolute inset-0 bg-gradient-to-t from-black/70 via-transparent to-transparent" />

      <div className="relative grid min-h-[27rem] gap-8 p-7 sm:p-10 lg:min-h-[31rem] lg:grid-cols-[1fr_16rem] lg:p-12">
        <div
          key={game.sku}
          aria-roledescription="slide"
          aria-label={`${String(index + 1)} of ${String(games.length)}: ${game.title}`}
          className="flex max-w-2xl animate-rise flex-col justify-end"
        >
          <p className="flex items-center gap-2 text-xs font-semibold tracking-[0.18em] text-white/70 uppercase">
            <Icon name="sparkle" className="size-4" />
            Featured · {game.genre}
          </p>
          <h2 className="mt-3 text-4xl font-extrabold tracking-tight text-balance text-white sm:text-5xl lg:text-6xl">
            {game.title}
          </h2>
          <p className="mt-2 text-sm text-white/65">
            {game.studio} · {releaseDate(game.releasedOn)}
          </p>
          <p className="mt-4 max-w-xl text-base text-pretty text-white/85 sm:text-lg">{game.blurb}</p>
          <div className="mt-7 flex flex-wrap items-center gap-3">
            <Price priceCents={game.priceCents} listPriceCents={game.listPriceCents} currency={game.currency} size="md" inverse />
            <Link to={`/games/${game.sku}`} className={button('primary', 'lg', 'sm:ml-2')}>
              View game
            </Link>
            {availability.max > 0 && (
              <button
                type="button"
                onClick={() => {
                  addToCart(game)
                }}
                className={button('secondary', 'lg', 'border-white/25 bg-white/10 text-white backdrop-blur hover:bg-white/20')}
              >
                <Icon name="cart" />
                Add to cart
              </button>
            )}
          </div>
        </div>

        <ol className="hidden flex-col justify-end gap-2 lg:flex" aria-label="Choose a featured game">
          {games.map((featured, i) => (
            <li key={featured.sku}>
              <button
                type="button"
                aria-current={i === index}
                aria-label={`Show ${featured.title}`}
                onClick={() => {
                  setIndex(i)
                }}
                className={`flex w-full items-center gap-3 rounded-xl p-2 text-left transition ${
                  i === index ? 'bg-white/20 ring-1 ring-white/40' : 'hover:bg-white/10'
                }`}
              >
                <GameCover sku={featured.sku} motif={featured.cover} className="h-14 w-11 shrink-0 rounded-md" />
                <span className="min-w-0">
                  <span className="block truncate text-sm font-semibold text-white">{featured.title}</span>
                  <span className="block truncate text-xs text-white/60">{featured.genre}</span>
                </span>
              </button>
            </li>
          ))}
        </ol>
      </div>

      <div className="absolute right-7 bottom-6 flex gap-2 lg:hidden">
        {games.map((featured, i) => (
          <button
            key={featured.sku}
            type="button"
            aria-label={`Show ${featured.title}`}
            aria-current={i === index}
            onClick={() => {
              setIndex(i)
            }}
            className={`h-1.5 rounded-full transition-all ${i === index ? 'w-6 bg-white' : 'w-1.5 bg-white/45'}`}
          />
        ))}
      </div>
    </section>
  )
}

export function HeroSkeleton() {
  return <Skeleton className="min-h-[27rem] rounded-3xl lg:min-h-[31rem]" />
}
