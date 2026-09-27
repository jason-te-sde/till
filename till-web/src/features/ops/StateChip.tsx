import type { ReservationState } from '../../api/types'

const COLOUR: Record<ReservationState, string> = {
  // Held is the ordinary state and gets an ordinary colour; spending a status hue on the common case
  // would leave nothing louder for the ones worth looking at.
  HELD: 'var(--series-available)',
  COMMITTED: 'var(--status-good)',
  EXPIRED: 'var(--status-warning)',
  RELEASED: 'var(--ink-muted)',
}

/**
 * A reservation's state as a word first and a colour second.
 *
 * The case worth looking at is `EXPIRED` beside a stored `HELD`. That is not a glitch: the deadline
 * decides, and the row has not been written off because nothing has needed to yet. The console shows
 * both because the difference is the design.
 */
export function StateChip({ state, effectiveState }: { state: ReservationState; effectiveState: ReservationState }) {
  const stale = effectiveState !== state
  return (
    <span className="inline-flex items-center gap-1.5 whitespace-nowrap">
      <span aria-hidden="true" className="size-2 shrink-0 rounded-full" style={{ background: COLOUR[effectiveState] }} />
      <span className="text-xs font-semibold tracking-wide">{effectiveState}</span>
      {stale && (
        <span
          className="text-xs text-ink-muted"
          title="The deadline has passed; the stored row still says HELD because nothing has needed to write it off yet."
        >
          (stored {state})
        </span>
      )}
    </span>
  )
}
