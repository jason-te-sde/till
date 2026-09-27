import { useState, type ReactNode } from 'react'
import { Link, useSearchParams } from 'react-router'
import { problemOf } from '../../api/problem'
import { useSearchGamesQuery } from '../../api/storeApi'
import { useTitle } from '../../app/useTitle'
import { button } from '../../components/button'
import { Icon } from '../../components/Icon'
import { Modal } from '../../components/Modal'
import { EmptyState, ErrorNotice } from '../../components/States'
import { plural } from '../../format'
import { PAGE_SIZE, PRICE_CAPS, SORTS, headingFor, pageWindow, readBrowse, toSearch, writeBrowse, type Browse } from './browse'
import { Filters } from './Filters'
import { GameCard, GameCardSkeleton } from './GameCard'

/**
 * Browsing and search: every filter, the sort and the page live in the URL.
 *
 * While a new page of results loads, the previous one stays on screen, dimmed, rather than collapsing
 * to skeletons — a filter click that blanks the grid for 200 milliseconds reads as a flicker, not as
 * progress.
 */
export function BrowsePage() {
  const [params, setParams] = useSearchParams()
  const browse = readBrowse(params)
  const heading = headingFor(browse)
  useTitle(heading)
  const [filtersOpen, setFiltersOpen] = useState(false)
  const { data, error, isFetching, refetch } = useSearchGamesQuery(toSearch(browse))
  const problem = problemOf(error)

  function change(patch: Partial<Browse>) {
    // Any change but a page change starts again from the first page: page 3 of a different filter is
    // not somewhere anybody meant to go.
    setParams(writeBrowse({ ...browse, page: 1, ...patch }))
  }

  const genres = data?.facets.genres ?? []
  const tags = data?.facets.tags ?? []
  const pages = data === undefined ? 0 : Math.ceil(data.total / PAGE_SIZE)
  const chips = activeChips(browse)

  return (
    <div className="grid gap-8 lg:grid-cols-[14.5rem_1fr]">
      <aside className="hidden lg:block" aria-label="Filters">
        <div className="sticky top-24">
          <Filters browse={browse} genres={genres} tags={tags} onChange={change} />
        </div>
      </aside>

      <div className="min-w-0">
        <div className="flex flex-wrap items-end justify-between gap-4">
          <div>
            <h1 className="text-2xl font-bold tracking-tight sm:text-3xl">{heading}</h1>
            <p className="mt-1 text-sm text-ink-secondary" aria-live="polite">
              {data === undefined ? 'Searching…' : plural(data.total, 'game')}
            </p>
          </div>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={() => {
                setFiltersOpen(true)
              }}
              className={button('secondary', 'md', 'lg:hidden')}
            >
              <Icon name="filter" className="size-4" />
              Filters
            </button>
            <label className="flex items-center gap-2 text-sm text-ink-secondary">
              <span className="hidden sm:inline">Sort by</span>
              <select
                value={browse.sort === '' ? (browse.q.trim() === '' ? 'featured' : 'relevance') : browse.sort}
                onChange={(event) => {
                  change({ sort: event.target.value as Browse['sort'] })
                }}
                className="h-10 rounded-lg border border-line-strong bg-surface px-3 text-sm font-medium text-ink"
              >
                {SORTS.filter((sort) => sort.needsText !== true || browse.q.trim() !== '').map((sort) => (
                  <option key={sort.value} value={sort.value}>
                    {sort.label}
                  </option>
                ))}
              </select>
            </label>
          </div>
        </div>

        {chips.length > 0 && (
          <div className="mt-4 flex flex-wrap items-center gap-2">
            {chips.map((chip) => (
              <button
                key={chip.label}
                type="button"
                onClick={() => {
                  change(chip.clear)
                }}
                className="inline-flex items-center gap-1.5 rounded-full bg-accent-soft px-3 py-1 text-sm font-medium text-accent-soft-ink hover:brightness-110"
                aria-label={`Remove filter: ${chip.label}`}
              >
                {chip.label}
                <Icon name="x" className="size-3.5" />
              </button>
            ))}
            <Link to="/browse" className="text-sm font-medium text-ink-secondary hover:text-ink hover:underline">
              Clear all
            </Link>
          </div>
        )}

        <div className="mt-6">
          {problem !== undefined && data === undefined ? (
            <ErrorNotice
              problem={problem}
              onRetry={() => {
                void refetch()
              }}
            />
          ) : data === undefined ? (
            <Grid>
              {Array.from({ length: 8 }, (_, i) => (
                <GameCardSkeleton key={i} />
              ))}
            </Grid>
          ) : data.items.length === 0 ? (
            <EmptyState
              icon="search"
              title="No games match"
              action={
                <Link to="/browse" className={button('secondary')}>
                  Clear the filters
                </Link>
              }
            >
              {browse.q.trim() !== ''
                ? `Nothing matches “${browse.q.trim()}” with these filters. Try fewer words, or a different spelling.`
                : 'Nothing matches every filter at once. Loosen one and try again.'}
            </EmptyState>
          ) : (
            <div className={`transition-opacity duration-200 ${isFetching ? 'opacity-60' : ''}`} aria-busy={isFetching}>
              <Grid>
                {data.items.map((game) => (
                  <GameCard key={game.sku} game={game} />
                ))}
              </Grid>
            </div>
          )}
        </div>

        {pages > 1 && (
          <nav aria-label="Pages" className="mt-10 flex items-center justify-center gap-1">
            <PageLink page={browse.page - 1} disabled={browse.page <= 1} browse={browse} label="Previous page">
              <Icon name="chevronLeft" className="size-4" />
            </PageLink>
            {pageWindow(browse.page, pages).map((page, i) =>
              page === null ? (
                <span key={`gap-${String(i)}`} className="px-2 text-ink-muted">
                  …
                </span>
              ) : (
                <PageLink key={page} page={page} browse={browse} current={page === browse.page} label={`Page ${String(page)}`}>
                  {page}
                </PageLink>
              ),
            )}
            <PageLink page={browse.page + 1} disabled={browse.page >= pages} browse={browse} label="Next page">
              <Icon name="chevronRight" className="size-4" />
            </PageLink>
          </nav>
        )}
      </div>

      <Modal
        open={filtersOpen}
        onClose={() => {
          setFiltersOpen(false)
        }}
        title="Filters"
        side
        footer={
          <button
            type="button"
            onClick={() => {
              setFiltersOpen(false)
            }}
            className={button('primary', 'md', 'w-full')}
          >
            {data === undefined ? 'Show results' : `Show ${plural(data.total, 'game')}`}
          </button>
        }
      >
        <Filters browse={browse} genres={genres} tags={tags} onChange={change} />
      </Modal>
    </div>
  )
}

function Grid({ children }: { children: ReactNode }) {
  return <div className="grid grid-cols-2 gap-x-4 gap-y-8 sm:grid-cols-3 xl:grid-cols-4">{children}</div>
}

function PageLink({
  page,
  browse,
  label,
  current = false,
  disabled = false,
  children,
}: {
  page: number
  browse: Browse
  label: string
  current?: boolean
  disabled?: boolean
  children: ReactNode
}) {
  const shape = 'grid h-10 min-w-10 place-items-center rounded-lg px-3 text-sm font-semibold'
  if (disabled) {
    return (
      <span aria-disabled="true" aria-label={label} className={`${shape} text-ink-muted opacity-50`}>
        {children}
      </span>
    )
  }
  return (
    <Link
      to={`/browse?${writeBrowse({ ...browse, page }).toString()}`}
      aria-label={label}
      aria-current={current ? 'page' : undefined}
      className={`${shape} ${current ? 'bg-accent text-accent-ink' : 'text-ink-secondary hover:bg-sunken hover:text-ink'}`}
    >
      {children}
    </Link>
  )
}

function activeChips(browse: Browse): { label: string; clear: Partial<Browse> }[] {
  const chips: { label: string; clear: Partial<Browse> }[] = []
  if (browse.q.trim() !== '') chips.push({ label: `“${browse.q.trim()}”`, clear: { q: '' } })
  if (browse.genre !== '') chips.push({ label: browse.genre, clear: { genre: '' } })
  if (browse.maxPrice !== undefined) {
    const cap = PRICE_CAPS.find((candidate) => candidate.cents === browse.maxPrice)
    chips.push({ label: cap?.label ?? 'Price cap', clear: { maxPrice: undefined } })
  }
  if (browse.onSale) chips.push({ label: 'On sale', clear: { onSale: false } })
  if (browse.tag !== '') chips.push({ label: `#${browse.tag}`, clear: { tag: '' } })
  return chips
}
