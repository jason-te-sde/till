import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router'
import { problemOf } from '../../api/problem'
import { useGetGameQuery } from '../../api/storeApi'
import type { GameCard as Game } from '../../api/types'
import { useAppSelector } from '../../app/hooks'
import { useTitle } from '../../app/useTitle'
import { button } from '../../components/button'
import { Icon } from '../../components/Icon'
import { Price } from '../../components/Price'
import { QuantityStepper } from '../../components/QuantityStepper'
import { EmptyState, ErrorNotice, Skeleton } from '../../components/States'
import { ago, releaseDate } from '../../format'
import { selectCartLines } from '../cart/cartSlice'
import { useAddToCart } from '../cart/useAddToCart'
import { availabilityOf } from './availability'
import { GameCover } from './GameCover'
import { Shelf } from './Shelf'

/** How often an open game page asks again how many are left — while it is the tab being looked at. */
const AVAILABILITY_POLL_MS = 20_000

/**
 * One game: the art, the pitch, what it costs and how many are left, and a way to buy it.
 */
export function GamePage() {
  const { sku = '' } = useParams()
  const { data, error, refetch } = useGetGameQuery(sku, {
    pollingInterval: AVAILABILITY_POLL_MS,
    skipPollingIfUnfocused: true,
  })
  useTitle(data?.game.title)
  const problem = problemOf(error)

  if (problem?.code === 'NOT_FOUND') {
    return (
      <EmptyState
        icon="search"
        title="We couldn't find that game"
        action={
          <Link to="/browse" className={button('primary')}>
            Browse the store
          </Link>
        }
      >
        It may have left the catalogue, or the link may be mistyped.
      </EmptyState>
    )
  }
  if (problem !== undefined && data === undefined) {
    return (
      <ErrorNotice
        problem={problem}
        onRetry={() => {
          void refetch()
        }}
      />
    )
  }
  if (data === undefined) {
    return <GamePageSkeleton />
  }

  const { game } = data
  return (
    <div className="space-y-14">
      <section className="relative isolate -mx-4 overflow-hidden px-4 pt-2 pb-10 sm:-mx-6 sm:rounded-3xl sm:px-8 sm:pt-8">
        <div aria-hidden="true" className="absolute inset-0 -z-10 opacity-60 blur-2xl saturate-150">
          <GameCover sku={game.sku} motif={game.cover} className="size-full scale-110" />
        </div>
        <div aria-hidden="true" className="absolute inset-0 -z-10 bg-gradient-to-b from-plane/40 via-plane/80 to-plane" />

        <nav aria-label="Breadcrumb" className="mb-6 text-sm text-ink-secondary">
          <Link to="/browse" className="hover:text-ink hover:underline">
            Store
          </Link>
          <span className="mx-2 text-ink-muted">/</span>
          <Link to={`/browse?genre=${encodeURIComponent(game.genre)}`} className="hover:text-ink hover:underline">
            {game.genre}
          </Link>
          <span className="mx-2 text-ink-muted">/</span>
          <span className="text-ink">{game.title}</span>
        </nav>

        <div className="grid gap-8 lg:grid-cols-[minmax(0,20rem)_1fr_20rem] lg:gap-10">
          <GameCover sku={game.sku} motif={game.cover} className="aspect-[3/4] w-full max-w-xs rounded-2xl shadow-pop ring-1 ring-black/10" />

          <div className="min-w-0">
            <p className="text-sm font-semibold text-accent">{game.genre}</p>
            <h1 className="mt-1 text-4xl font-extrabold tracking-tight text-balance sm:text-5xl">{game.title}</h1>
            <p className="mt-2 text-ink-secondary">
              by <span className="font-medium text-ink">{game.studio}</span> · Released {releaseDate(game.releasedOn)}
            </p>
            <p className="mt-5 text-lg text-pretty text-ink-secondary">{game.blurb}</p>
            <ul className="mt-5 flex flex-wrap gap-2" aria-label="Tags">
              {game.tags.map((tag) => (
                <li key={tag}>
                  <Link
                    to={`/browse?tag=${encodeURIComponent(tag)}`}
                    className="inline-block rounded-full border border-hairline bg-surface/70 px-3 py-1 text-xs font-medium text-ink-secondary backdrop-blur hover:border-line-strong hover:text-ink"
                  >
                    {tag}
                  </Link>
                </li>
              ))}
            </ul>
          </div>

          {/* Keyed, so moving from one game's page to another's starts the quantity again at one. */}
          <PurchasePanel key={game.sku} game={game} />
        </div>
      </section>

      <div className="grid gap-10 lg:grid-cols-[1fr_20rem]">
        <section aria-labelledby="about-heading">
          <h2 id="about-heading" className="text-xl font-bold tracking-tight">
            About this game
          </h2>
          <div className="mt-3 space-y-4 text-pretty text-ink-secondary">
            {data.description.split(/\n\s*\n/).map((paragraph, i) => (
              <p key={i}>{paragraph}</p>
            ))}
          </div>
          {data.features.length > 0 && (
            <ul className="mt-6 grid gap-3 sm:grid-cols-3">
              {data.features.map((feature) => (
                <li key={feature} className="flex gap-2.5 rounded-xl border border-hairline bg-surface p-4 text-sm">
                  <Icon name="check" className="mt-0.5 size-4 shrink-0 text-good-ink" />
                  {feature}
                </li>
              ))}
            </ul>
          )}
        </section>

        <section aria-labelledby="details-heading" className="rounded-2xl border border-hairline bg-surface p-5">
          <h2 id="details-heading" className="font-semibold">
            Details
          </h2>
          <dl className="mt-3 divide-y divide-hairline text-sm">
            {[
              ['Studio', game.studio],
              ['Genre', game.genre],
              ['Released', releaseDate(game.releasedOn)],
              ['Product code', game.sku],
            ].map(([term, value]) => (
              <div key={term} className="flex justify-between gap-4 py-2.5">
                <dt className="text-ink-muted">{term}</dt>
                <dd className="text-right font-medium">{value}</dd>
              </div>
            ))}
          </dl>
        </section>
      </div>

      {data.related.length > 0 && (
        <Shelf title="More like this" subtitle={`More ${game.genre}, and games that share its tags`} games={data.related} />
      )}
    </div>
  )
}

function PurchasePanel({ game }: { game: Game }) {
  const availability = availabilityOf(game)
  const inCart = useAppSelector(selectCartLines).find((line) => line.sku === game.sku)?.quantity ?? 0
  const [chosen, setChosen] = useState(1)
  const addToCart = useAddToCart()
  const navigate = useNavigate()
  const quantity = Math.min(chosen, Math.max(1, availability.max))
  const buyable = availability.max > 0

  const tone = {
    good: 'text-good-ink',
    low: 'text-warning-ink',
    out: 'text-critical-ink',
    unknown: 'text-ink-muted',
  }[availability.tone]

  return (
    <aside aria-label="Buy" className="h-fit rounded-2xl border border-hairline bg-surface/90 p-5 shadow-card backdrop-blur lg:sticky lg:top-24">
      <Price priceCents={game.priceCents} listPriceCents={game.listPriceCents} currency={game.currency} size="lg" />
      {game.discountPercent > 0 && (
        <p className="mt-1 text-sm text-ink-secondary">You save {game.discountPercent}% on the list price.</p>
      )}

      <p className={`mt-4 flex items-center gap-2 text-sm font-semibold ${tone}`}>
        <span aria-hidden="true" className="size-2 rounded-full bg-current" />
        {availability.label}
      </p>
      {game.availabilityAsOf !== null && (
        <p className="mt-0.5 text-xs text-ink-muted" title="Availability is the inventory ledger's last word, streamed to the store.">
          Updated {ago(game.availabilityAsOf)}
        </p>
      )}

      {buyable && (
        <div className="mt-5 flex items-center justify-between gap-3">
          <span className="text-sm text-ink-secondary">Quantity</span>
          <QuantityStepper value={quantity} max={availability.max} onChange={setChosen} label={`copies of ${game.title}`} />
        </div>
      )}

      <div className="mt-5 grid gap-2">
        <button
          type="button"
          disabled={!buyable}
          onClick={() => {
            addToCart(game, quantity)
          }}
          className={button('primary', 'lg', 'w-full')}
        >
          <Icon name="cart" />
          {buyable ? 'Add to cart' : availability.label}
        </button>
        {buyable && (
          <button
            type="button"
            onClick={() => {
              addToCart(game, quantity)
              void navigate('/checkout')
            }}
            className={button('secondary', 'lg', 'w-full')}
          >
            Buy now
          </button>
        )}
      </div>
      {inCart > 0 && (
        <p className="mt-3 text-center text-sm text-ink-secondary">
          <Icon name="check" className="mr-1 inline size-4 text-good-ink" />
          {inCart} in your cart
        </p>
      )}

      <div className="mt-5 space-y-2 border-t border-hairline pt-4 text-xs text-ink-secondary">
        <p className="flex gap-2">
          <Icon name="clock" className="size-4 shrink-0 text-ink-muted" />
          Placing an order holds your copies for 15 minutes while you pay.
        </p>
        <p className="flex gap-2">
          <Icon name="shield" className="size-4 shrink-0 text-ink-muted" />
          Demonstration store: payment is simulated.
        </p>
      </div>
    </aside>
  )
}

function GamePageSkeleton() {
  return (
    <div className="grid gap-8 pt-10 lg:grid-cols-[20rem_1fr_20rem]" aria-busy="true" aria-label="Loading">
      <Skeleton className="aspect-[3/4] w-full max-w-xs rounded-2xl" />
      <div className="space-y-4">
        <Skeleton className="h-4 w-24" />
        <Skeleton className="h-12 w-3/4" />
        <Skeleton className="h-4 w-1/2" />
        <Skeleton className="h-20 w-full" />
      </div>
      <Skeleton className="h-80 rounded-2xl" />
    </div>
  )
}
