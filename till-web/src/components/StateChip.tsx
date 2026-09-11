import type { ReservationState } from '../api/client'

/**
 * A reservation's state, as a word first and a colour second.
 *
 * The colour is always beside the word, never instead of it — which is what the status palette
 * requires, and which also happens to be the only way this reads in a screenshot or a print-out.
 *
 * The case worth looking at is `EXPIRED` beside a stored `HELD`. That is not a glitch: the deadline
 * decides, and the row has not been written off yet because nothing has needed to. The console shows
 * both because the difference is the design, and hiding it would make the sweeper look mandatory.
 */
export function StateChip({
  state,
  effectiveState,
}: {
  state: ReservationState
  effectiveState?: ReservationState
}) {
  const shown = effectiveState ?? state
  const stale = effectiveState !== undefined && effectiveState !== state

  return (
    <span className="inline-flex items-center gap-1.5 whitespace-nowrap">
      <span
        aria-hidden="true"
        className="size-2 shrink-0 rounded-full"
        style={{ background: colourFor(shown) }}
      />
      <span className="text-xs font-medium tracking-wide">{shown}</span>
      {stale && (
        <span className="text-xs text-ink-muted" title="The deadline has passed; the row still says HELD because nothing has needed to write it off yet.">
          (stored {state})
        </span>
      )}
    </span>
  )
}

function colourFor(state: ReservationState): string {
  switch (state) {
    case 'COMMITTED':
      return 'var(--status-good)'
    case 'EXPIRED':
      return 'var(--status-warning)'
    case 'RELEASED':
      return 'var(--ink-muted)'
    case 'HELD':
    default:
      // Held is the ordinary state and gets an ordinary colour. Spending a status hue on the common
      // case leaves nothing louder for the cases that are actually worth looking at.
      return 'var(--series-available)'
  }
}
