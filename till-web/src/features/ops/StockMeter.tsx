import type { OpsStock } from '../../api/types'

/**
 * On-hand stock, split into what is available and what is spoken for.
 *
 * A two-segment stacked bar rather than two bars or a percentage: the two numbers are the two halves of
 * one quantity, and what an operator wants at a glance is the ratio. Both values are also printed in the
 * columns beside it — the direct labelling that makes colour a second channel rather than the only one.
 *
 * A 2px gap in the surface colour between the segments so they never appear to merge; rounded ends on
 * the outside only, so the bar stays anchored to its track; a track in the sunken surface so an empty
 * row still reads as a row.
 */
export function StockMeter({ stock }: { stock: OpsStock }) {
  const total = stock.onHand
  const available = total === 0 ? 0 : (Math.max(0, stock.available) / total) * 100
  const reserved = total === 0 ? 0 : (stock.reserved / total) * 100

  return (
    <div className="group/meter relative">
      <div
        className="flex h-2.5 w-full overflow-hidden rounded-full bg-sunken"
        role="img"
        aria-label={`${String(stock.available)} available, ${String(stock.reserved)} reserved, ${String(total)} on hand`}
      >
        {available > 0 && (
          <div
            className="h-full rounded-l-full"
            style={{
              width: `${String(available)}%`,
              background: 'var(--series-available)',
              borderRight: reserved > 0 ? '2px solid var(--surface)' : undefined,
            }}
          />
        )}
        {reserved > 0 && (
          <div className="h-full rounded-r-full" style={{ width: `${String(reserved)}%`, background: 'var(--series-reserved)' }} />
        )}
      </div>
      <div
        role="tooltip"
        className="pointer-events-none absolute bottom-full left-0 z-10 mb-1.5 hidden rounded-md border border-hairline bg-raised px-2 py-1 text-xs whitespace-nowrap shadow-sm group-hover/meter:block"
      >
        <span className="numeric text-ink">
          {stock.available} available · {stock.reserved} reserved · {total} on hand
        </span>
      </div>
    </div>
  )
}

/** The legend: present because there are two series, placed once above the table, word beside swatch. */
export function StockLegend() {
  return (
    <div className="flex items-center gap-4 text-xs text-ink-secondary">
      {[
        ['var(--series-available)', 'Available'],
        ['var(--series-reserved)', 'Reserved'],
      ].map(([colour, label]) => (
        <span key={label} className="inline-flex items-center gap-1.5">
          <span aria-hidden="true" className="size-2.5 rounded-sm" style={{ background: colour }} />
          {label}
        </span>
      ))}
    </div>
  )
}
