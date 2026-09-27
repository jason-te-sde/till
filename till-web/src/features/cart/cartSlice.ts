import { createSelector, createSlice, type PayloadAction } from '@reduxjs/toolkit'
import type { GameCard } from '../../api/types'

/** The store refuses more than this many different games in one order; the cart says so first. */
export const MAX_LINES = 20

/** …and more than this many copies of one game. */
export const MAX_QUANTITY = 10

/**
 * One game in the cart, with enough of the catalogue copied in to draw it without asking the server.
 *
 * The copied price is for display only. The order is priced by the store from its own catalogue when
 * it is placed — a cart edited in local storage can change what this page shows, and nothing else.
 */
export interface CartLine {
  readonly sku: string
  readonly title: string
  readonly studio: string
  readonly cover: string
  readonly priceCents: number
  readonly listPriceCents: number
  readonly quantity: number
}

export interface CartState {
  readonly lines: readonly CartLine[]
}

const initialState: CartState = { lines: [] }

/**
 * The cart: which games, how many of each.
 *
 * Redux rather than component state because four unrelated parts of the page read it — the header's
 * count, the drawer, the checkout and the game page's "in your cart" — and one of them, the checkout,
 * has to replace it wholesale when the store says a game has sold out.
 *
 * Limits are enforced here, where the numbers change, rather than by disabling buttons: a button can
 * be enabled by a stale render, a reducer cannot be argued with.
 */
export const cartSlice = createSlice({
  name: 'cart',
  initialState,
  reducers: {
    added(state, action: PayloadAction<{ game: GameCard; quantity?: number }>) {
      const { game, quantity = 1 } = action.payload
      const existing = state.lines.find((line) => line.sku === game.sku)
      const snapshot = {
        sku: game.sku,
        title: game.title,
        studio: game.studio,
        cover: game.cover,
        priceCents: game.priceCents,
        listPriceCents: game.listPriceCents,
      }
      if (existing !== undefined) {
        // Refreshed while it is here: the price shown should be the one the customer just looked at.
        Object.assign(existing, snapshot, { quantity: clamp(existing.quantity + quantity) })
      } else if (state.lines.length < MAX_LINES) {
        state.lines.push({ ...snapshot, quantity: clamp(quantity) })
      }
    },
    quantitySet(state, action: PayloadAction<{ sku: string; quantity: number }>) {
      const line = state.lines.find((candidate) => candidate.sku === action.payload.sku)
      if (line !== undefined) {
        line.quantity = clamp(action.payload.quantity)
      }
    },
    removed(state, action: PayloadAction<string>) {
      state.lines = state.lines.filter((line) => line.sku !== action.payload)
    },
    cleared(state) {
      state.lines = []
    },
    /** The whole cart at once — from another tab, or from local storage at start-up. */
    replaced(state, action: PayloadAction<readonly CartLine[]>) {
      state.lines = action.payload.slice(0, MAX_LINES).map((line) => ({ ...line, quantity: clamp(line.quantity) }))
    },
  },
  selectors: {
    selectCartLines: (cart) => cart.lines,
  },
})

export const { added, quantitySet, removed, cleared, replaced } = cartSlice.actions
export const { selectCartLines } = cartSlice.selectors

/** Copies, not lines: three of one game is three in the header's badge. */
export const selectCartCount = createSelector([selectCartLines], (lines) =>
  lines.reduce((count, line) => count + line.quantity, 0),
)

/** What the cart costs at the prices it was filled at — the order's own total is the one that counts. */
export const selectCartSubtotal = createSelector([selectCartLines], (lines) =>
  lines.reduce((total, line) => total + line.priceCents * line.quantity, 0),
)

/** What the discounts in the cart save, against list price. */
export const selectCartSavings = createSelector([selectCartLines], (lines) =>
  lines.reduce((saved, line) => saved + (line.listPriceCents - line.priceCents) * line.quantity, 0),
)

function clamp(quantity: number): number {
  if (!Number.isFinite(quantity)) {
    return 1
  }
  return Math.min(MAX_QUANTITY, Math.max(1, Math.trunc(quantity)))
}
