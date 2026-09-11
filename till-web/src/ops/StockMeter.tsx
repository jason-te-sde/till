import type { Stock } from '../api/client'

/**
 * On-hand stock, split into what is available and what is spoken for.
 *
 * A two-segment stacked bar rather than two bars or a percentage: the two numbers are the two halves
 * of one quantity, and the thing an operator wants to see at a glance is the ratio, not either value.
 * Both values are also printed in the columns beside it, which is the direct labelling that makes the
 * colour a second channel rather than the only one.
 *
 * Mark specification: a 2px gap in the surface colour between the segments so they never appear to
 * merge, rounded ends on the outside only so the bar stays anchored to its track, and a track in the
 * sunken surface so an empty SKU still reads as a row rather than as nothing.
 */
export function StockMeter({ stock }: { stock: Stock }) {
  const total = stock.onHand
  const available = total === 0 ? 0 : (stock.available / total) * 100
  const reserved = total === 0 ? 0 : (stock.reserved / total) * 100

  return (
    <div className="group/meter relative">
      <div
        className="flex h-2.5 w-full overflow-hidden rounded-full bg-sunken"
        role="img"
        aria-label={`${stock.sku}: ${String(stock.available)} available, ${String(stock.reserved)} reserved, ${String(total)} on hand`}
      >
        {available > 0 && (
          <div
            className="h-full rounded-l-full"
            style={{
              width: `${String(available)}%`,
              background: 'var(--series-available)',
              // The gap is drawn rather than spaced, so the two segments still add up to the width.
              borderRight: reserved > 0 ? '2px solid var(--surface)' : undefined,
            }}
          />
        )}
        {reserved > 0 && (
          <div
            className="h-full rounded-r-full"
            style={{ width: `${String(reserved)}%`, background: 'var(--series-reserved)' }}
          />
        )}
      </div>

      <div
        role="tooltip"
        className="pointer-events-none absolute bottom-full left-0 z-10 mb-1.5 hidden whitespace-nowrap rounded-md border border-hairline bg-surface px-2 py-1 text-xs shadow-sm group-hover/meter:block"
      >
        <span className="numeric text-ink">
          {stock.available} available · {stock.reserved} reserved · {total} on hand
        </span>
      </div>
    </div>
  )
}

/**
 * The legend.
 *
 * Present because there are two series, and placed once above the table rather than repeated per
 * row. Identity never rests on colour alone: the swatch sits beside its word here, and every number
 * appears in a labelled column below.
 */
export function StockLegend() {
  return (
    <div className="flex items-center gap-4 text-xs text-ink-secondary">
      <Swatch colour="var(--series-available)" label="Available" />
      <Swatch colour="var(--series-reserved)" label="Reserved" />
    </div>
  )
}

function Swatch({ colour, label }: { colour: string; label: string }) {
  return (
    <span className="inline-flex items-center gap-1.5">
      <span aria-hidden="true" className="size-2.5 rounded-sm" style={{ background: colour }} />
      {label}
    </span>
  )
}
