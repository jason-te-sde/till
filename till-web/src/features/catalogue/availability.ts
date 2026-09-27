import { MAX_QUANTITY } from '../cart/cartSlice'

/** At or below this, the page says how many are left rather than just "in stock". */
export const LOW_STOCK = 5

export type AvailabilityTone = 'good' | 'low' | 'out' | 'unknown'

export interface Availability {
  readonly tone: AvailabilityTone
  readonly label: string
  /** The most copies the page will offer; zero when there is nothing to offer. */
  readonly max: number
}

/**
 * What a game's page should say about stock, from what the store last heard from the ledger.
 *
 * This is a hint, and the page treats it as one. The number comes from the event stream and lags the
 * ledger by however long an event takes to arrive; the ledger decides for real at checkout, which is
 * why a stale "3 left" costs a customer one refused order and never an oversold one.
 *
 * @param game the game, as the catalogue sent it
 * @returns what to show
 */
export function availabilityOf(game: { available: number; availabilityAsOf: string | null }): Availability {
  if (game.availabilityAsOf === null) {
    // The store has never heard of stock for this game — not the same as having heard there is none,
    // though to a customer it means the same thing.
    return { tone: 'unknown', label: 'Not in stock yet', max: 0 }
  }
  if (game.available <= 0) {
    return { tone: 'out', label: 'Sold out', max: 0 }
  }
  const max = Math.min(MAX_QUANTITY, game.available)
  if (game.available <= LOW_STOCK) {
    return { tone: 'low', label: `Only ${String(game.available)} left`, max }
  }
  return { tone: 'good', label: 'In stock', max }
}
