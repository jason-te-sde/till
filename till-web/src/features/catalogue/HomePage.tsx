import { problemOf } from '../../api/problem'
import { useGetHomeQuery } from '../../api/storeApi'
import { useTitle } from '../../app/useTitle'
import { Icon, type IconName } from '../../components/Icon'
import { ErrorNotice, Skeleton } from '../../components/States'
import { GenreGrid } from './GenreGrid'
import { Hero, HeroSkeleton } from './Hero'
import { Shelf } from './Shelf'

const PROMISES: { icon: IconName; title: string; body: string }[] = [
  { icon: 'clock', title: 'Held while you pay', body: 'Placing an order sets your copies aside for 15 minutes.' },
  { icon: 'shield', title: 'Safe to retry', body: 'A double click or a dropped connection never orders twice.' },
  { icon: 'bolt', title: 'Live stock', body: 'Availability streams in from the inventory ledger as it changes.' },
  { icon: 'info', title: 'A demonstration store', body: 'Payment is simulated. No card is ever asked for.' },
]

/**
 * The front of the store, in one request: the featured games, what is on sale, what is selling, the
 * genres and what is new. One round trip because it is the page most people land on, and a home page
 * assembled from five requests is five chances to show a half-drawn page.
 */
export function HomePage() {
  useTitle(undefined)
  const { data, error, refetch } = useGetHomeQuery()
  const problem = problemOf(error)

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

  return (
    <div className="space-y-14">
      <h1 className="sr-only">till games</h1>
      {data === undefined ? <HeroSkeleton /> : <Hero games={data.featured} />}

      <ul className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        {PROMISES.map((promise) => (
          <li key={promise.title} className="flex gap-3 rounded-2xl border border-hairline bg-surface p-4">
            <span className="grid size-10 shrink-0 place-items-center rounded-xl bg-accent-soft text-accent-soft-ink">
              <Icon name={promise.icon} />
            </span>
            <span>
              <span className="block text-sm font-semibold">{promise.title}</span>
              <span className="mt-0.5 block text-sm text-ink-secondary">{promise.body}</span>
            </span>
          </li>
        ))}
      </ul>

      <Shelf title="On sale" subtitle="Discounted now, for as long as it lasts" to="/browse?onSale=true" games={data?.onSale} />
      <Shelf title="Best sellers" subtitle="What sold most in the last seven days" to="/browse?sort=bestselling" games={data?.bestSellers} ranked />
      {data === undefined ? <Skeleton className="h-64 rounded-2xl" /> : <GenreGrid genres={data.genres} />}
      <Shelf title="New releases" subtitle="Just out, and coming soon" to="/browse?sort=newest" games={data?.newReleases} />
    </div>
  )
}
