import { useId } from 'react'
import type { Facet } from '../../api/types'
import { PRICE_CAPS, type Browse } from './browse'

/**
 * The browse page's filters: genre, price, discount and tag.
 *
 * Counts come from the server's facets, which count each genre under every *other* filter — so picking
 * "Puzzle" does not reduce the other genres to zero, and a customer can see what switching would give
 * them before they switch.
 */
export function Filters({
  browse,
  genres,
  tags,
  onChange,
}: {
  browse: Browse
  genres: readonly Facet[]
  tags: readonly Facet[]
  onChange: (patch: Partial<Browse>) => void
}) {
  const name = useId()
  return (
    <div className="space-y-7">
      <fieldset>
        <legend className="text-xs font-semibold tracking-wider text-ink-muted uppercase">Genre</legend>
        <div className="mt-2 space-y-0.5">
          <Choice
            name={`${name}-genre`}
            label="All genres"
            checked={browse.genre === ''}
            onSelect={() => {
              onChange({ genre: '' })
            }}
          />
          {genres.map((genre) => (
            <Choice
              key={genre.value}
              name={`${name}-genre`}
              label={genre.value}
              count={genre.count}
              checked={browse.genre === genre.value}
              onSelect={() => {
                onChange({ genre: genre.value })
              }}
            />
          ))}
        </div>
      </fieldset>

      <fieldset>
        <legend className="text-xs font-semibold tracking-wider text-ink-muted uppercase">Price</legend>
        <div className="mt-2 space-y-0.5">
          <Choice
            name={`${name}-price`}
            label="Any price"
            checked={browse.maxPrice === undefined}
            onSelect={() => {
              onChange({ maxPrice: undefined })
            }}
          />
          {PRICE_CAPS.map((cap) => (
            <Choice
              key={cap.cents}
              name={`${name}-price`}
              label={cap.label}
              checked={browse.maxPrice === cap.cents}
              onSelect={() => {
                onChange({ maxPrice: cap.cents })
              }}
            />
          ))}
        </div>
      </fieldset>

      <fieldset>
        <legend className="text-xs font-semibold tracking-wider text-ink-muted uppercase">Deals</legend>
        <label className="mt-2 flex cursor-pointer items-center gap-2.5 rounded-lg px-2 py-1.5 text-sm hover:bg-sunken">
          <input
            type="checkbox"
            checked={browse.onSale}
            onChange={(event) => {
              onChange({ onSale: event.target.checked })
            }}
            className="size-4 accent-[var(--accent)]"
          />
          On sale only
        </label>
      </fieldset>

      {tags.length > 0 && (
        <fieldset>
          <legend className="text-xs font-semibold tracking-wider text-ink-muted uppercase">Tags</legend>
          <div className="mt-3 flex flex-wrap gap-1.5">
            {tags.slice(0, 14).map((tag) => {
              const active = browse.tag === tag.value
              return (
                <button
                  key={tag.value}
                  type="button"
                  aria-pressed={active}
                  onClick={() => {
                    onChange({ tag: active ? '' : tag.value })
                  }}
                  className={`rounded-full border px-2.5 py-1 text-xs font-medium transition ${
                    active
                      ? 'border-transparent bg-accent text-accent-ink'
                      : 'border-hairline bg-surface text-ink-secondary hover:border-line-strong hover:text-ink'
                  }`}
                >
                  {tag.value}
                  <span className={`ml-1 ${active ? 'opacity-80' : 'text-ink-muted'}`}>{tag.count}</span>
                </button>
              )
            })}
          </div>
        </fieldset>
      )}
    </div>
  )
}

function Choice({
  name,
  label,
  count,
  checked,
  onSelect,
}: {
  name: string
  label: string
  count?: number
  checked: boolean
  onSelect: () => void
}) {
  return (
    <label
      className={`flex cursor-pointer items-center gap-2.5 rounded-lg px-2 py-1.5 text-sm transition ${
        checked ? 'bg-accent-soft font-semibold text-accent-soft-ink' : 'text-ink-secondary hover:bg-sunken hover:text-ink'
      }`}
    >
      <input type="radio" name={name} checked={checked} onChange={onSelect} className="sr-only" />
      <span className="flex-1">{label}</span>
      {count !== undefined && <span className="numeric text-xs text-ink-muted">{count}</span>}
    </label>
  )
}
