import { compact } from '../format'

/**
 * The four numbers an operator looks at first.
 *
 * Bare stat tiles: a label, a value, and a line saying what the value means. No sparkline and no
 * delta, because there is no history to compare against — a tile showing "+12% vs last period" when
 * nothing records a last period is decoration pretending to be information.
 *
 * Values use the font's proportional figures rather than tabular ones. Tabular figures give every
 * digit the width of a zero, which at this size makes a number like 121 look full of holes; they
 * belong in the columns below, where digits have to line up.
 */
export interface Stat {
  readonly label: string
  readonly value: number | string
  readonly caption: string
}

export function StatTiles({ stats }: { stats: readonly Stat[] }) {
  return (
    <dl className="grid grid-cols-2 gap-3 lg:grid-cols-4">
      {stats.map((stat) => (
        <div
          key={stat.label}
          className="rounded-lg border border-hairline bg-surface px-4 py-3"
        >
          <dt className="text-xs font-medium tracking-wide text-ink-muted uppercase">{stat.label}</dt>
          <dd className="mt-1 text-3xl font-semibold text-ink">
            {typeof stat.value === 'number' ? compact(stat.value) : stat.value}
          </dd>
          <p className="mt-0.5 text-xs text-ink-secondary">{stat.caption}</p>
        </div>
      ))}
    </dl>
  )
}
